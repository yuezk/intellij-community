// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(FlowPreview::class)

package com.intellij.openapi.wm.impl

import com.intellij.diagnostic.StartUpMeasurer
import com.intellij.diagnostic.rootTask
import com.intellij.ide.DataManager
import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.UISettingsListener
import com.intellij.ide.ui.customization.CustomActionsSchema
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.actionSystem.impl.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.mac.screenmenu.MenuBar
import com.intellij.util.PlatformUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import org.jetbrains.concurrency.await
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.MenuSelectionManager
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal interface ActionAwareIdeMenuBar {
  fun updateMenuActions(forceRebuild: Boolean = false)
}

internal interface IdeMenuFlavor {
  val state: IdeMenuBarState
    get() = IdeMenuBarState.EXPANDED

  fun jMenuSelectionChanged(isIncluded: Boolean) {
  }

  fun getPreferredSize(size: Dimension): Dimension = size

  fun updateAppMenu()

  fun layoutClockPanelAndButton() {
  }

  fun correctMenuCount(menuCount: Int): Int = menuCount

  fun suspendAnimator() {}
}

internal sealed class IdeMenuBarHelper(@JvmField val flavor: IdeMenuFlavor,
                                       @JvmField internal val menuBar: MenuBarImpl) : ActionAwareIdeMenuBar {
  protected abstract fun isUpdateForbidden(): Boolean

  @JvmField
  protected var visibleActions = emptyList<ActionGroup>()

  @JvmField
  protected val presentationFactory: PresentationFactory = MenuItemPresentationFactory()

  private val updateRequests = MutableSharedFlow<Boolean>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  interface MenuBarImpl {
    val frame: JFrame

    val coroutineScope: CoroutineScope
    val isDarkMenu: Boolean
    val component: JComponent

    fun updateGlobalMenuRoots()

    suspend fun getMainMenuActionGroup(): ActionGroup?
  }

  init {
    val app = ApplicationManager.getApplication()
    val coroutineScope = menuBar.coroutineScope
    @Suppress("IfThenToSafeAccess")
    if (app != null) {
      app.messageBus.connect(coroutineScope).subscribe(UISettingsListener.TOPIC, UISettingsListener {
        check(updateRequests.tryEmit(true))
      })
    }

    coroutineScope.launch(ModalityState.any().asContextElement()) {
      withContext(if (StartUpMeasurer.isEnabled()) (rootTask() + CoroutineName("ide menu bar actions init")) else EmptyCoroutineContext) {
        val mainActionGroup = menuBar.getMainMenuActionGroup()
        val actions = updateMenuActions(mainActionGroup = mainActionGroup, forceRebuild = false, isFirstUpdate = true)
        postInitActions(actions)
      }

      val actionManager = serviceAsync<ActionManager>()
      if (actionManager is ActionManagerEx) {
        coroutineScope.launch {
          actionManager.timerEvents.collect {
            updateOnTimer()
          }
        }
      }

      updateRequests
        .debounce(50.milliseconds)
        .collect { forceRebuild ->
          presentationFactory.reset()
          updateMenuActions(mainActionGroup = menuBar.getMainMenuActionGroup(), forceRebuild = forceRebuild, isFirstUpdate = false)
        }
    }
  }

  private suspend fun updateOnTimer() {
    withContext(Dispatchers.EDT) {
      if (!menuBar.frame.isShowing || !menuBar.frame.isActive) {
        return@withContext
      }

      // do not update when a popup menu is shown
      // (if a popup menu contains action which is also in the menu bar, it should not be enabled/disabled)
      if (isUpdateForbidden()) {
        return@withContext
      }

      // don't update the toolbar if there is currently active modal dialog
      val window = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
      if (window !is Dialog || !window.isModal) {
        updateRequests.emit(false)
      }
    }
  }

  final override fun updateMenuActions(forceRebuild: Boolean) {
    check(updateRequests.tryEmit(forceRebuild))
  }

  protected open suspend fun postInitActions(actions: List<ActionGroup>) {
  }

  abstract suspend fun updateMenuActions(mainActionGroup: ActionGroup?, forceRebuild: Boolean, isFirstUpdate: Boolean): List<ActionGroup>

  protected fun createActionMenuList(newVisibleActions: List<ActionGroup>, consumer: (ActionMenu) -> Unit) {
    if (newVisibleActions.isEmpty()) {
      return
    }

    val enableMnemonics = !UISettings.getInstance().disableMnemonics
    val isCustomDecorationActive = IdeFrameDecorator.isCustomDecorationActive()
    for (action in newVisibleActions) {
      val actionMenu = ActionMenu(null, ActionPlaces.MAIN_MENU, action, presentationFactory, enableMnemonics, menuBar.isDarkMenu, true)
      if (isCustomDecorationActive) {
        actionMenu.isOpaque = false
        actionMenu.isFocusable = false
      }
      consumer(actionMenu)
    }
  }
}

internal open class JMenuBasedIdeMenuBarHelper(flavor: IdeMenuFlavor, menuBar: MenuBarImpl) : IdeMenuBarHelper(flavor, menuBar) {
  override fun isUpdateForbidden() = MenuSelectionManager.defaultManager().selectedPath.isNotEmpty()

  override suspend fun postInitActions(actions: List<ActionGroup>) {
    withContext(Dispatchers.EDT) {
      for (action in actions) {
        PopupMenuPreloader.install(menuBar.component, ActionPlaces.MAIN_MENU, null) { action }
      }
    }
  }

  override suspend fun updateMenuActions(mainActionGroup: ActionGroup?, forceRebuild: Boolean, isFirstUpdate: Boolean): List<ActionGroup> {
    val menuBarComponent = menuBar.component
    val newVisibleActions = mainActionGroup?.let {
      expandMainActionGroup(mainActionGroup = it,
                            menuBar = menuBarComponent,
                            frame = menuBar.frame,
                            presentationFactory = presentationFactory,
                            isFirstUpdate = isFirstUpdate)
    } ?: emptyList()

    if (!forceRebuild && newVisibleActions == visibleActions && !presentationFactory.isNeedRebuild) {
      val enableMnemonics = !UISettings.getInstance().disableMnemonics
      withContext(Dispatchers.EDT) {
        for (child in menuBarComponent.components) {
          if (child is ActionMenu) {
            child.updateFromPresentation(enableMnemonics)
          }
        }
      }
      return newVisibleActions
    }

    // should rebuild UI
    val changeBarVisibility = newVisibleActions.isEmpty() || visibleActions.isEmpty()
    withContext(Dispatchers.EDT) {
      visibleActions = newVisibleActions
      menuBarComponent.removeAll()
      createActionMenuList(newVisibleActions) {
        menuBarComponent.add(it)
      }
      presentationFactory.resetNeedRebuild()
      flavor.updateAppMenu()
      menuBar.updateGlobalMenuRoots()
      menuBarComponent.validate()
      if (changeBarVisibility) {
        menuBarComponent.invalidate()
        menuBar.frame.validate()
      }
    }
    return newVisibleActions
  }
}

internal open class PeerBasedIdeMenuBarHelper(private val screenMenuPeer: MenuBar,
                                              flavor: IdeMenuFlavor,
                                              menuBar: MenuBarImpl) : IdeMenuBarHelper(flavor, menuBar) {
  override fun isUpdateForbidden() = screenMenuPeer.isAnyChildOpened

  override suspend fun updateMenuActions(mainActionGroup: ActionGroup?, forceRebuild: Boolean, isFirstUpdate: Boolean): List<ActionGroup> {
    val newVisibleActions = mainActionGroup?.let {
      expandMainActionGroup(mainActionGroup = it,
                            menuBar = menuBar.component,
                            frame = menuBar.frame,
                            presentationFactory = presentationFactory,
                            isFirstUpdate = isFirstUpdate)
    } ?: emptyList()

    if (!forceRebuild && newVisibleActions == visibleActions && !presentationFactory.isNeedRebuild) {
      return newVisibleActions
    }

    withContext(Dispatchers.EDT) {
      visibleActions = newVisibleActions
      screenMenuPeer.beginFill()
      try {
        createActionMenuList(newVisibleActions) {
          screenMenuPeer.add(it.screenMenuPeer)
        }
      }
      finally {
        screenMenuPeer.endFill()
        presentationFactory.resetNeedRebuild()
      }
      flavor.updateAppMenu()
    }
    return newVisibleActions
  }
}

private val firstUpdateFastTrackUpdateTimeout = 30.seconds.inWholeMilliseconds

private suspend fun expandMainActionGroup(mainActionGroup: ActionGroup,
                                          menuBar: Component,
                                          frame: JFrame,
                                          presentationFactory: PresentationFactory,
                                          isFirstUpdate: Boolean): List<ActionGroup> {
  // don't repeat for JetBrains Client - deadlock possible
  repeat(if (PlatformUtils.isJetBrainsClient()) 1 else 3) {
    presentationFactory.resetNeedRebuild()
    try {
      return withContext(CoroutineName("expandMainActionGroup") + Dispatchers.EDT) {
        val targetComponent = WindowManager.getInstance().getFocusedComponent(frame) ?: menuBar
        val dataContext = Utils.wrapToAsyncDataContext(DataManager.getInstance().getDataContext(targetComponent))
        // disable fast track for JetBrains Client - deadlock otherwise
        val fastTrackTimeout = when {
          // disable fast track for JetBrains Client - deadlock otherwise
          PlatformUtils.isJetBrainsClient() -> -1
          isFirstUpdate -> firstUpdateFastTrackUpdateTimeout
          else -> Utils.getFastTrackTimeout()
        }
        Utils.expandActionGroupAsync(/* group = */ mainActionGroup,
                                     /* presentationFactory = */ presentationFactory,
                                     /* context = */ dataContext,
                                     /* place = */ ActionPlaces.MAIN_MENU,
                                     /* isToolbarAction = */ false,
                                     /* fastTrackTimeout = */ fastTrackTimeout)
      }.await().filterIsInstance<ActionGroup>()
    }
    catch (e: ProcessCanceledException) {
      if (isFirstUpdate) {
        logger<IdeMenuBarHelper>().warn("Cannot expand action group", e)
        return emptyList()
      }
    }
  }
  return emptyList()
}

internal suspend fun getMainMenuActionGroup(frame: JFrame): ActionGroup? {
  val group = (frame.rootPane as? IdeRootPane)?.mainMenuActionGroup
  return group ?: CustomActionsSchema.getInstanceAsync().getCorrectedActionAsync(IdeActions.GROUP_MAIN_MENU)
}
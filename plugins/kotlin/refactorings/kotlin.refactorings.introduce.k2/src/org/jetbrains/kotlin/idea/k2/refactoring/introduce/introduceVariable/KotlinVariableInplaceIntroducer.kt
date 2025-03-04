// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.k2.refactoring.introduce.introduceVariable

import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateBuilderImpl
import com.intellij.codeInsight.template.TemplateEditingAdapter
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil
import com.intellij.refactoring.rename.inplace.TemplateInlayUtil.SelectableTemplateElement
import com.intellij.refactoring.rename.inplace.TemplateInlayUtil.createNavigatableButtonWithPopup
import com.intellij.refactoring.rename.inplace.TemplateInlayUtil.createSettingsPresentation
import com.intellij.ui.dsl.builder.actionListener
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import org.jetbrains.kotlin.analysis.api.analyzeInModalWindow
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsights.impl.base.CallableReturnTypeUpdaterUtils
import org.jetbrains.kotlin.idea.refactoring.KotlinCommonRefactoringSettings
import org.jetbrains.kotlin.idea.refactoring.introduce.AbstractKotlinInplaceIntroducer
import org.jetbrains.kotlin.idea.util.application.executeWriteCommand
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.createSmartPointer
import org.jetbrains.kotlin.psi.psiUtil.isIdentifier
import org.jetbrains.kotlin.psi.psiUtil.quoteIfNeeded
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import javax.swing.JCheckBox
import javax.swing.JPanel

class KotlinVariableInplaceIntroducer(
    addedVariable: KtProperty,
    originalExpression: KtExpression?,
    occurrencesToReplace: Array<KtExpression>,
    suggestedNames: Collection<String>,
    private val typeString: String?,
    project: Project,
    editor: Editor
) : AbstractKotlinInplaceIntroducer<KtProperty>(
    localVariable = addedVariable.takeIf { it.isLocal },
    expression = originalExpression,
    occurrences = occurrencesToReplace,
    title = KotlinIntroduceVariableHandler.INTRODUCE_VARIABLE,
    project = project,
    editor = editor,
) {
    private val suggestedNames = suggestedNames.toTypedArray()
    private var expressionTypeCheckBox: JCheckBox? = null
    private val addedVariablePointer = addedVariable.createSmartPointer()
    private val addedVariable get() = addedVariablePointer.element

    private fun createPopupPanel(): DialogPanel {
        return panel {
            row {
                checkBox(KotlinBundle.message("checkbox.text.declare.with.var"))
                    .selected(KotlinCommonRefactoringSettings.getInstance().INTRODUCE_DECLARE_WITH_VAR)
                    .actionListener { _, component ->
                        myProject.executeWriteCommand(commandName, commandName) {
                            PsiDocumentManager.getInstance(myProject).commitDocument(myEditor.document)

                            val psiFactory = KtPsiFactory(myProject)
                            val keyword = if (component.isSelected) psiFactory.createVarKeyword() else psiFactory.createValKeyword()
                            addedVariable?.valOrVarKeyword?.replace(keyword)
                            KotlinCommonRefactoringSettings.getInstance().INTRODUCE_DECLARE_WITH_VAR = component.isSelected
                        }
                    }
            }
            if (typeString != null) {
                row {
                    checkBox(KotlinBundle.message("specify.type.explicitly")).apply {
                        selected(KotlinCommonRefactoringSettings.getInstance().INTRODUCE_SPECIFY_TYPE_EXPLICITLY)
                        actionListener { _, component ->
                            runWriteCommandAndRestart {
                                updateVariableName()
                                if (component.isSelected) {
                                    addedVariable?.typeReference = KtPsiFactory(myProject).createType(typeString)
                                } else {
                                    addedVariable?.typeReference = null
                                }
                                KotlinCommonRefactoringSettings.getInstance().INTRODUCE_SPECIFY_TYPE_EXPLICITLY = component.isSelected
                            }
                        }
                    }
                }
            }
        }
    }

    override fun getVariable() = addedVariable

    override fun suggestNames(replaceAll: Boolean, variable: KtProperty?) = suggestedNames

    override fun createFieldToStartTemplateOn(replaceAll: Boolean, names: Array<out String>) = addedVariable

    override fun addAdditionalVariables(builder: TemplateBuilderImpl) {
        val variable = addedVariable ?: return
        variable.typeReference?.let {
            //val expression = SpecifyTypeExplicitlyIntention.createTypeExpressionForTemplate(expressionType!!, variable) ?: return@let
            //builder.replaceElement(it, "TypeReferenceVariable", expression, false)
        }
    }

    override fun buildTemplateAndStart(
        refs: Collection<PsiReference>,
        stringUsages: Collection<Pair<PsiElement, TextRange>>,
        scope: PsiElement,
        containingFile: PsiFile
    ): Boolean {
        myNameSuggestions = myNameSuggestions.mapTo(LinkedHashSet(), String::quoteIfNeeded)

        myEditor.caretModel.moveToOffset(nameIdentifier!!.startOffset)

        val result = super.buildTemplateAndStart(refs, stringUsages, scope, containingFile)
        val variable = addedVariable
        val templateState = TemplateManagerImpl.getTemplateState(InjectedLanguageUtil.getTopLevelEditor(myEditor))
        if (templateState != null && variable?.typeReference != null) {
            templateState.addTemplateStateListener(createTypeReferencePostprocessor(variable))
        }

        return result
    }

    private fun createTypeReferencePostprocessor(
        variable: KtCallableDeclaration,
    ): TemplateEditingAdapter = object : TemplateEditingAdapter() {
        override fun templateFinished(template: Template, brokenOff: Boolean) {
            val context = analyzeInModalWindow(variable, KotlinBundle.message("find.usages.prepare.dialog.progress")) {
                CallableReturnTypeUpdaterUtils.getTypeInfo(variable)
            }
            ApplicationManager.getApplication().runWriteAction {
                CallableReturnTypeUpdaterUtils.updateType(variable, context, myProject, myEditor)
            }
        }
    }

    override fun afterTemplateStart() {
        super.afterTemplateStart()
        val templateState = TemplateManagerImpl.getTemplateState(myEditor) ?: return

        val currentVariableRange = templateState.currentVariableRange ?: return

        val popupComponent = createPopupPanel()

        val presentation = createSettingsPresentation(templateState.editor as EditorImpl) {}
        val templateElement: SelectableTemplateElement = object : SelectableTemplateElement(presentation) {
        }
        createNavigatableButtonWithPopup(
            templateState,
            currentVariableRange.endOffset,
            presentation,
            popupComponent as JPanel,
            templateElement
        ) { }
    }

    override fun getInitialName() = super.getInitialName().quoteIfNeeded()

    override fun updateTitle(variable: KtProperty?, value: String?) {
        expressionTypeCheckBox?.isEnabled = value == null || value.isIdentifier() // No preview to update
    }

    override fun deleteTemplateField(psiField: KtProperty?) { // Do not delete introduced variable as it was created outside of in-place refactoring
    }

    override fun isReplaceAllOccurrences() = true

    override fun setReplaceAllOccurrences(allOccurrences: Boolean) {

    }

    override fun getComponent() = myWholePanel

    override fun performIntroduce() {
        val newName = inputName ?: return
        val replacement = KtPsiFactory(myProject).createExpression(newName)

        ApplicationManager.getApplication().runWriteAction {
            addedVariable?.setName(newName)
            occurrences.forEach {
                if (it.isValid) {
                    it.replace(replacement)
                }
            }
        }
    }
}

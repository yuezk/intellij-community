// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui;

import com.intellij.ide.ui.NotRoamableUiSettings;
import com.intellij.ide.ui.UISettings;
import com.intellij.ide.ui.laf.LafManagerImpl;
import com.intellij.testFramework.LightPlatformTestCase;

import javax.swing.*;
import java.awt.*;

/**
 * @author Konstantin Bulenkov
 */
public class LafManagerTest extends LightPlatformTestCase {
  public void testCustomFont() {
    UISettings uiSettings = UISettings.getInstance();
    String fontFace = uiSettings.getFontFace();
    int fontSize = UISettings.getInstance().getFontSize();
    LafManagerImpl lafManager = LafManagerImpl.getTestInstance();

    try {
      String newFontName = "Arial";
      int newFontSize = 17;
      uiSettings.setFontFace(newFontName);
      uiSettings.setFontSize(newFontSize);
      NotRoamableUiSettings.Companion.getInstance().setOverrideLafFonts(true);
      lafManager.updateUI();
      Font font = UIManager.getFont("Label.font");
      assertEquals("Font name is not changed", newFontName, font.getName());
      assertEquals("Font size is not changed", newFontSize, font.getSize());
    } finally {
      NotRoamableUiSettings.Companion.getInstance().setOverrideLafFonts(false);
      uiSettings.setFontFace(fontFace);
      uiSettings.setFontSize(fontSize);
      lafManager.updateUI();
    }
  }
}

package com.codex.remote

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HomeDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun label(id: Int) = rule.activity.getString(id)
    private fun waitForKeyboard() = rule.waitUntil(5_000) {
        androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
    }

    @Test fun appearanceSwitchPersistsAfterActivityRecreation() {
        val original = com.codex.remote.ui.AppAppearance.selected(rule.activity)
        rule.onNodeWithTag("home-menu").performClick()
        rule.onNodeWithText(label(R.string.ui_253)).performClick()
        try {
            for (mode in listOf("dark", "light", "system")) {
                rule.onNodeWithTag("app-theme-$mode").performScrollTo().performClick().assertIsSelected()
                rule.activityRule.scenario.recreate()
                rule.onNodeWithTag("app-theme-$mode").performScrollTo().assertIsSelected()
                rule.onNodeWithTag("app-language").performScrollTo().assertIsDisplayed()
                java.io.File(rule.activity.getExternalFilesDir(null), "appearance-$mode.png").outputStream().use {
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally { rule.runOnUiThread { com.codex.remote.ui.AppAppearance.select(rule.activity, original) } }
    }

    @Test fun languageCanBeChangedInSettingsAndSurvivesRecreation() {
        val original = com.codex.remote.ui.AppLanguage.selected(rule.activity)
        rule.onNodeWithTag("home-menu").performClick()
        rule.onNodeWithText(label(R.string.ui_253)).performClick()
        try {
            for ((tag, title) in listOf("en" to "Settings", "ja" to "設定", "zh" to "设置")) {
                rule.onNodeWithTag("app-language").performScrollTo().performClick()
                rule.onNodeWithTag("app-language-$tag").performClick()
                rule.waitUntil(10_000) { rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithText(title).assertIsDisplayed()
                rule.activityRule.scenario.recreate()
                rule.waitUntil(10_000) { rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
                assertTrue(com.codex.remote.ui.AppLanguage.selected(rule.activity) == tag)
                java.io.File(rule.activity.getExternalFilesDir(null), "codeb-language-$tag.png").outputStream().use {
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            rule.onNodeWithTag("app-language").performScrollTo().performClick()
            rule.onNodeWithTag("app-language-system").performClick()
            rule.waitUntil(10_000) { com.codex.remote.ui.AppLanguage.selected(rule.activity).isEmpty() }
        } finally {
            rule.runOnUiThread { com.codex.remote.ui.AppLanguage.select(rule.activity, original) }
        }
    }

    @Test fun draftSurvivesSettingsAndActivityRecreation() {
        rule.onNodeWithContentDescription(label(R.string.ui_259)).performClick()
        val draft = "device-check-${UUID.randomUUID()}"
        rule.onNode(hasSetTextAction()).performTextInput(draft)
        rule.onNodeWithTag("home-menu").performClick()
        rule.onNodeWithText(label(R.string.ui_253)).performClick()
        rule.onNodeWithContentDescription(label(R.string.ui_151)).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(draft).fetchSemanticsNodes().isNotEmpty() }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(draft).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(label(R.string.ui_114)).performClick()
        rule.onNodeWithText(label(R.string.ui_258)).performClick()
        rule.onAllNodesWithTag("home-mode-work").assertCountEquals(0)
        rule.onNodeWithContentDescription(label(R.string.ui_114)).performClick()
        rule.onNodeWithText(label(R.string.file_manager)).performClick()
        rule.onNode(isDialog()).assertExists()
        rule.onNodeWithText(label(R.string.file_manager)).assertIsDisplayed()
    }

    @Test fun providerEditorSaveStaysInsideWindowWithKeyboard() {
        rule.onNodeWithTag("home-menu").performClick()
        rule.onNodeWithText(label(R.string.ui_253)).performClick()
        rule.onNodeWithText(label(R.string.ui_261)).performClick()
        rule.onNodeWithText(label(R.string.ui_281)).performClick()
        waitForKeyboard()
        rule.waitForIdle()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText(label(R.string.ui_038)).assertIsDisplayed() }.isSuccess }
        val save = rule.onNodeWithText(label(R.string.ui_038)).fetchSemanticsNode().boundsInRoot
        val roots = rule.onAllNodes(isRoot()).fetchSemanticsNodes().map { it.boundsInRoot }
        val file = java.io.File(rule.activity.getExternalFilesDir(null), "qa-provider.png")
        file.outputStream().use { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val root = roots.last()
        assertTrue("save=$save, roots=$roots", save.left >= root.left && save.right <= root.right && save.bottom <= root.bottom)
        rule.onNodeWithText(label(R.string.ui_038)).assertIsDisplayed()
    }

    @Test fun sshEditorSaveStaysVisibleWithKeyboard() {
        rule.onNodeWithTag("home-menu").performClick()
        rule.onNodeWithText(label(R.string.ui_227)).performClick()
        rule.onNodeWithContentDescription(label(R.string.ui_003)).performClick()
        rule.onNodeWithText(label(R.string.ui_020)).performClick()
        waitForKeyboard()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText(label(R.string.ui_038)).assertIsDisplayed() }.isSuccess }
        rule.onNodeWithText(label(R.string.ui_345)).assertIsDisplayed()
        val file = java.io.File(rule.activity.getExternalFilesDir(null), "qa-ssh.png")
        file.outputStream().use { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun sidebarCoversHeaderAndSettingsListsSavedProviders() {
        rule.onNodeWithTag("home-menu").performClick()
        rule.onNodeWithText(label(R.string.app_name)).assertIsDisplayed()
        val menu = rule.onNodeWithText(label(R.string.app_name)).fetchSemanticsNode().boundsInRoot
        assertTrue(menu.top < rule.activity.window.decorView.height / 2)
        rule.onNodeWithText(label(R.string.ui_253)).performClick()
        rule.onNodeWithText(label(R.string.api_providers)).assertIsDisplayed()
        rule.onNodeWithText(label(R.string.ui_261)).assertIsDisplayed()
        val file = java.io.File(rule.activity.getExternalFilesDir(null), "qa-settings.png")
        file.outputStream().use { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}

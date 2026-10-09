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

    @Test fun draftSurvivesModeSwitchAndActivityRecreation() {
        rule.onNodeWithTag("home-mode-chat").performClick()
        rule.onNodeWithContentDescription(label(R.string.ui_259)).performClick()
        val draft = "device-check-${UUID.randomUUID()}"
        rule.onNode(hasSetTextAction()).performTextInput(draft)
        rule.onNodeWithTag("home-mode-work").performClick()
        rule.onNodeWithTag("home-mode-chat").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(draft).fetchSemanticsNodes().isNotEmpty() }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(draft).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(label(R.string.ui_114)).performClick()
        rule.onNodeWithText(label(R.string.ui_258)).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("home-mode-work").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(label(R.string.ui_114)).performClick()
        rule.onNodeWithText(label(R.string.ui_257)).performClick()
        rule.onNodeWithText(label(R.string.ui_269)).assertIsDisplayed()
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
        rule.onNodeWithText("Codex Remote").assertIsDisplayed()
        val menu = rule.onNodeWithText("Codex Remote").fetchSemanticsNode().boundsInRoot
        val modes = rule.onNodeWithTag("home-mode-chat").fetchSemanticsNode().boundsInRoot
        assertTrue(menu.top < modes.bottom)
        rule.onNodeWithText(label(R.string.ui_253)).performClick()
        rule.onNodeWithText(label(R.string.api_providers)).assertIsDisplayed()
        rule.onNodeWithText(label(R.string.ui_261)).assertIsDisplayed()
        val file = java.io.File(rule.activity.getExternalFilesDir(null), "qa-settings.png")
        file.outputStream().use { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}

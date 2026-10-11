package com.codex.remote

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.codex.remote.domain.*
import com.codex.remote.ui.screens.ApprovalDialog
import com.codex.remote.ui.theme.CodexRemoteTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QuestionDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun multipleQuestionsScrollAndSubmitChoicesAndFreeText() {
        var sent: Map<String, List<String>>? = null
        val request = ApprovalRequest("question", ApprovalKind.USER_INPUT, "Choose", "", "item/tool/requestUserInput",
            questions = listOf(
                ApprovalQuestion("choice", "Choice", "Pick one", listOf("A", "B"), listOf("First option details", "Second option details")),
                ApprovalQuestion("text", "Details", "Add your answer")))
        rule.setContent { CodexRemoteTheme { ApprovalDialog(request, { _, answers -> sent = answers }) } }
        rule.onNodeWithTag("question-submit").assertIsNotEnabled()
        rule.onNodeWithText("Second option details").performClick()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { screenshot ->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "v31-question.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
        rule.onNodeWithTag("question-answer-text").performScrollTo().performTextInput("Phone answer")
        rule.onNodeWithTag("question-submit").assertIsEnabled().performClick()
        assertEquals(mapOf("choice" to listOf("B"), "text" to listOf("Phone answer")), sent)
    }
}

package com.codex.remote

import android.graphics.Typeface
import android.graphics.Bitmap
import android.content.ClipboardManager
import android.content.Context
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codex.remote.data.rpc.CodexRpcClient
import com.codex.remote.data.store.ConnectionStore
import com.codex.remote.data.store.LocalStore
import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.AuthType
import com.codex.remote.domain.ComposerImageAttachment
import com.codex.remote.domain.ComposerMention
import com.codex.remote.domain.ConnectionStatus
import com.codex.remote.domain.FileChangeSummary
import com.codex.remote.domain.PermissionMode
import com.codex.remote.domain.ReasoningEffortOption
import com.codex.remote.domain.RemoteAccount
import com.codex.remote.domain.RemoteCollaborationMode
import com.codex.remote.domain.RemoteModel
import com.codex.remote.domain.RemotePlugin
import com.codex.remote.domain.RemoteProject
import com.codex.remote.domain.RemoteServiceTier
import com.codex.remote.domain.RemoteSkill
import com.codex.remote.domain.RemoteThread
import com.codex.remote.domain.RemoteThreadTokenUsage
import com.codex.remote.domain.ReviewTargetKind
import com.codex.remote.domain.SavedConnection
import com.codex.remote.domain.ThreadGoal
import com.codex.remote.domain.ThreadGoalStatus
import com.codex.remote.domain.TimelineItem
import com.codex.remote.domain.TimelineKind
import com.codex.remote.domain.withThreadIndex
import com.codex.remote.domain.withStreamDelta
import com.codex.remote.domain.FailedSend
import com.codex.remote.ui.screens.WorkspaceScreen
import com.codex.remote.ui.theme.CodexRemoteTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.text.DateFormat
import java.io.File
import java.util.Date
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class WorkspaceDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun composerMatchesDesktopLayoutAndPlanIsCommandDriven() {
        val state = mutableStateOf(baseState())
        val callbacks = WorkspaceCallbacks()
        show(state, callbacks)

        composeRule.onAllNodesWithText("Default", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Plan", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("5.6 Sol Ultra").assertExists()
        composeRule.onAllNodesWithText("Fast").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Fast", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(COMPOSER_CONTEXT).assertContentDescriptionEquals("上下文已使用 64%")

        val add = composeRule.onNodeWithTag(COMPOSER_ADD).fetchSemanticsNode().boundsInRoot
        val permissions = composeRule.onNodeWithTag(COMPOSER_PERMISSIONS).fetchSemanticsNode().boundsInRoot
        val context = composeRule.onNodeWithTag(COMPOSER_CONTEXT).fetchSemanticsNode().boundsInRoot
        val model = composeRule.onNodeWithTag(COMPOSER_MODEL).fetchSemanticsNode().boundsInRoot
        val send = composeRule.onNodeWithTag(COMPOSER_SEND).fetchSemanticsNode().boundsInRoot
        assertTrue(add.center.x < permissions.center.x)
        assertTrue(permissions.center.x < context.center.x)
        assertTrue(context.center.x < model.center.x)
        assertTrue(model.center.x < send.center.x)

        composeRule.onNodeWithTag(COMPOSER_PERMISSIONS).performClick()
        composeRule.onNodeWithText("替我审批").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(PermissionMode.AUTO_REVIEW, callbacks.permissionMode) }

        composeRule.onNodeWithTag(COMPOSER_INPUT).performTextInput("/plan")
        composeRule.onNodeWithTag(COMPOSER_SEND).performClick()
        composeRule.runOnIdle { assertEquals("plan", callbacks.collaborationMode) }
        composeRule.onAllNodesWithText("Default", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Plan mode", substring = true).assertCountEquals(0)
    }

    @Test fun liveReplyAppearsBeforeTurnCompletesAndIndexRefreshKeepsIt() {
        val state = mutableStateOf(baseState(timeline = listOf(TimelineItem("prompt", TimelineKind.USER, body = "hello")), isTurnRunning = true))
        show(state)
        composeRule.runOnIdle {
            state.value = state.value.withThreadIndex(emptyList()).withStreamDelta("thread-a", "live-reply", "First chunk", TimelineKind.AGENT)
        }
        composeRule.waitUntil(5_000) { composeRule.activity.findViewById<View>(android.R.id.content).descendantTextViews().any { it.text.contains("First chunk") } }
        composeRule.runOnIdle { assertTrue(state.value.isTurnRunning) }
        composeRule.runOnIdle { state.value = state.value.withStreamDelta("thread-a", "live-reply", " and second chunk", TimelineKind.AGENT) }
        composeRule.waitUntil(5_000) { composeRule.activity.findViewById<View>(android.R.id.content).descendantTextViews().any { it.text.contains("First chunk and second chunk") } }
        composeRule.runOnIdle { assertTrue(state.value.isTurnRunning) }
    }

    @Test fun runningTaskAndProjectRemainVisibleAfterIndexRefresh() {
        val state = mutableStateOf(baseState(isTurnRunning = true).withThreadIndex(listOf(thread("thread-a", "Thread A"))))
        show(state, startOnTasksPage = true)
        composeRule.onNodeWithTag("thread-running-thread-a").assertIsDisplayed()
        composeRule.onNodeWithTag("project-running-/workspace/demo").assertIsDisplayed()
        composeRule.runOnIdle { state.value = state.value.copy(isTurnRunning = false).withThreadIndex(listOf(thread("thread-a", "Thread A"))) }
        composeRule.onAllNodesWithTag("thread-running-thread-a").assertCountEquals(0)
        composeRule.onAllNodesWithTag("project-running-/workspace/demo").assertCountEquals(0)
    }

    @Test fun failedMessageStaysVisibleAndCanBeRetried() {
        val state = mutableStateOf(baseState(timeline = listOf(TimelineItem("failed", TimelineKind.USER, body = "retry this", status = "sendFailed", title = "QA request failed")))
            .copy(failedSend = FailedSend("failed", "retry this", emptyList(), emptyList(), false)))
        val callbacks = WorkspaceCallbacks()
        show(state, callbacks)
        composeRule.onNodeWithText("QA request failed").assertIsDisplayed()
        composeRule.onNodeWithText("Retry message").performClick()
        composeRule.runOnIdle { assertEquals(listOf("retry this" to false), callbacks.sentMessages) }
    }

    @Test fun modelMenuIsAnchoredAndOffersReasoningModelAndSpeed() {
        val state = mutableStateOf(baseState())
        val callbacks = WorkspaceCallbacks()
        show(state, callbacks)
        composeRule.onNodeWithTag(COMPOSER_INPUT).performClick()
        composeRule.waitUntil(5_000) { keyboardVisible() }
        composeRule.onNodeWithTag(COMPOSER_MODEL).performClick()
        composeRule.onNodeWithTag(MODEL_MENU).assertIsDisplayed()
        assertTrue(keyboardVisible())
        composeRule.onNodeWithText("Configuration").assertDoesNotExist()
        composeRule.onNodeWithText("Done").assertDoesNotExist()
        composeRule.onNodeWithText("Ultra").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithText("Low").assertIsNotSelected()
        composeRule.onNodeWithText("Model").assertIsDisplayed()
        composeRule.onNodeWithText("Speed").assertIsDisplayed()

        saveScreenshot("qa-model-menu.png")

        composeRule.onNodeWithText("Model").performClick()
        composeRule.onNodeWithText("GPT-5.6-Sol").assertIsDisplayed().assertIsSelected().performClick()
        composeRule.runOnIdle { assertEquals("gpt-5.6-sol", callbacks.model) }
        composeRule.onNodeWithText("Model").performClick()
        composeRule.onNode(hasContentDescription("Back") and hasAnyAncestor(hasTestTag(MODEL_MENU))).performClick()
        composeRule.onNodeWithText("Low").performClick()
        composeRule.runOnIdle { assertEquals("low", callbacks.reasoningEffort) }

        composeRule.onNodeWithTag(COMPOSER_MODEL).performClick()
        composeRule.onNodeWithText("Low").assertIsSelected()
        composeRule.onNodeWithText("Speed").performClick()
        composeRule.onNodeWithText("Fast").assertIsSelected()
        saveScreenshot("qa-speed-menu.png")
        composeRule.onNodeWithText("Standard").assertIsDisplayed().assertIsNotSelected().performClick()
        composeRule.runOnIdle { assertEquals(null, callbacks.serviceTier) }
        composeRule.onAllNodesWithContentDescription("Fast", useUnmergedTree = true).assertCountEquals(0)

        composeRule.onNodeWithTag(COMPOSER_MODEL).performClick()
        composeRule.onNodeWithText("Speed").performClick()
        composeRule.onNodeWithText("Standard").assertIsSelected()
        composeRule.onNodeWithText("Fast").assertIsDisplayed().assertIsNotSelected().performClick()
        composeRule.runOnIdle { assertEquals("fast", callbacks.serviceTier) }
        composeRule.onNodeWithContentDescription("Fast", useUnmergedTree = true).assertExists()
    }

    @Test fun connectingKeepsCachedReplyAndDraftWithoutAConversationBanner() {
        val state = mutableStateOf(baseState(
            timeline = listOf(TimelineItem("cached", TimelineKind.AGENT, body = "Cached reply")),
        ).copy(connectionStatus = ConnectionStatus.CONNECTING, connectionMessage = "Connecting QA Host"))
        val callbacks = WorkspaceCallbacks()
        show(state, callbacks)

        composeRule.onNodeWithTag("timeline-agent-cached").assertIsDisplayed()
        composeRule.onAllNodesWithText("Connecting QA Host").assertCountEquals(0)
        composeRule.onNodeWithTag(COMPOSER_INPUT).assertIsDisplayed().assertIsEnabled().performTextInput("draft while connecting")
        composeRule.onNodeWithTag(COMPOSER_SEND).assertIsNotEnabled()
        assertEquals("draft while connecting", composerText())
        composeRule.runOnIdle {
            assertTrue(callbacks.sentMessages.isEmpty())
            state.value = state.value.copy(connectionStatus = ConnectionStatus.CONNECTED)
        }
        composeRule.onNodeWithTag(COMPOSER_SEND).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(listOf("draft while connecting" to false), callbacks.sentMessages) }
    }

    @Test fun disconnectedKeepsComposerTypeableButCannotSend() {
        val state = mutableStateOf(baseState().copy(connectionStatus = ConnectionStatus.DISCONNECTED))
        val callbacks = WorkspaceCallbacks()
        show(state, callbacks)

        composeRule.onNodeWithTag(COMPOSER_INPUT).assertIsDisplayed().assertIsEnabled().performTextInput("offline draft")
        composeRule.onNodeWithTag(COMPOSER_SEND).assertIsNotEnabled()
        assertEquals("offline draft", composerText())
        composeRule.runOnIdle {
            assertTrue(callbacks.sentMessages.isEmpty())
        }
    }

    @Test fun freshProjectKeepsPretypedDraftWhenConnectionResolvesItsPath() {
        val state = mutableStateOf(baseState(threads = emptyList()).copy(
            selectedThreadId = null,
            selectedProjectPath = null,
            connectionStatus = ConnectionStatus.CONNECTING,
        ))
        val callbacks = WorkspaceCallbacks()
        show(state, callbacks)
        composeRule.onNodeWithTag(COMPOSER_INPUT).performTextInput("draft before project loads")
        composeRule.onNodeWithTag(COMPOSER_SEND).assertIsNotEnabled()
        composeRule.runOnIdle {
            state.value = state.value.copy(
                selectedProjectPath = "/workspace/demo",
                connectionStatus = ConnectionStatus.CONNECTED,
            )
        }
        assertEquals("draft before project loads", composerText())
        composeRule.onNodeWithTag(COMPOSER_SEND).assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("draft before project loads" to false), callbacks.sentMessages)
        }
    }

    @Test fun completedReplyOwnsDurationEditedFilesCopyAndCompletionTime() {
        val completedAt = 1_791_522_420_000L
        val reply = "Updated the file."
        val state = mutableStateOf(baseState(timeline = listOf(
            TimelineItem("question", TimelineKind.USER, body = "Update the file", turnId = "t1"),
            TimelineItem("files", TimelineKind.FILE_CHANGE, status = "completed", turnId = "t1",
                fileChanges = listOf(FileChangeSummary("src/Main.kt", "update",
                    "--- a/src/Main.kt\n+++ b/src/Main.kt\n-old\n+new"))),
            TimelineItem("answer", TimelineKind.AGENT, body = reply, status = "completed", turnId = "t1",
                elapsedMs = 20_000, completedAt = completedAt),
        )))
        show(state)

        composeRule.onNodeWithTag("reply-duration-answer").assertIsDisplayed()
        assertTrue(composeRule.onNodeWithTag("reply-duration-answer").fetchSemanticsNode()
            .config[SemanticsProperties.Text].any { "20" in it.text })
        composeRule.onNodeWithTag("file-changes-reply-answer").assertIsDisplayed()
        composeRule.onAllNodesWithTag("file-changes-files").assertCountEquals(0)
        composeRule.onNodeWithText("src/Main.kt").assertDoesNotExist()
        composeRule.onNodeWithText("已编辑 1 个文件").performClick()
        composeRule.onNodeWithText("src/Main.kt").assertExists()
        composeRule.onNodeWithText("+new").assertExists()
        composeRule.onNodeWithTag("reply-time-answer").assertIsDisplayed()
        saveScreenshot("qa-reply-footer.png")
        composeRule.onNodeWithTag("reply-copy-answer").performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            val clipboard = composeRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(reply, clipboard.primaryClip?.getItemAt(0)?.coerceToText(composeRule.activity)?.toString())
        }
        composeRule.onNodeWithTag("reply-time-answer").assertIsDisplayed()
        val expectedTime = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(completedAt))
        assertEquals(expectedTime, composeRule.onNodeWithTag("reply-time-answer").fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text)
    }

    @Test fun runningConversationUsesDotsAndTheSentPromptCanBeCopied() {
        val prompt = "Please read this 🙂\nSecond line"
        val state = mutableStateOf(baseState(timeline = listOf(TimelineItem("question", TimelineKind.USER, body = prompt)), isTurnRunning = true))
        show(state)
        composeRule.onNodeWithTag("reply-loading-dots").assertIsDisplayed()
        composeRule.onNodeWithText("Working").assertDoesNotExist()
        composeRule.onNodeWithText("正在执行").assertDoesNotExist()
        composeRule.onNodeWithTag("prompt-copy-question").performClick()
        composeRule.runOnIdle {
            val clipboard = composeRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(prompt, clipboard.primaryClip?.getItemAt(0)?.coerceToText(composeRule.activity)?.toString())
        }
        composeRule.runOnIdle { state.value = state.value.copy(timeline = state.value.timeline + TimelineItem("answer", TimelineKind.AGENT, body = "Partial reply", status = "inProgress")) }
        composeRule.onNodeWithTag("reply-loading-dots").assertIsDisplayed()
    }

    @Test fun oneCopyButtonWaitsForTheWholeTurnAndFullyDisplayedReply() {
        val state = mutableStateOf(baseState(timeline = listOf(
            TimelineItem("question", TimelineKind.USER, body = "Question", turnId = "t1"),
            TimelineItem("commentary", TimelineKind.AGENT, body = "I will read it.", status = "completed", turnId = "t1"),
            TimelineItem("answer", TimelineKind.AGENT, body = "第一句。第二句还没有", status = "inProgress", turnId = "t1"),
        )).copy(isTurnRunning = true, activeTurnId = "t1"))
        show(state)
        composeRule.onNodeWithTag("reply-copy-commentary").assertDoesNotExist()
        composeRule.onNodeWithTag("reply-copy-answer").assertDoesNotExist()
        var visible = false
        composeRule.waitUntil(5_000) { composeRule.runOnUiThread {
            visible = composeRule.activity.window.decorView.descendantTextViews().any { it.text.toString() == "第一句。" }
        }; visible }
        composeRule.runOnUiThread { state.value = state.value.copy(timeline = state.value.timeline.map { if (it.id == "answer") it.copy(status = "completed") else it }) }
        composeRule.onNodeWithTag("reply-copy-answer").assertDoesNotExist()
        val full = "第一句。第二句完整显示🙂。"
        composeRule.runOnUiThread { state.value = state.value.copy(isTurnRunning = false, timeline = state.value.timeline.map { if (it.id == "answer") it.copy(body = full) else it }) }
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithTag("reply-copy-answer").assertExists() }.isSuccess }
        composeRule.onNodeWithTag("reply-copy-commentary").assertDoesNotExist()
        composeRule.runOnUiThread {
            val text = composeRule.activity.window.decorView.descendantTextViews().single { it.text.toString() == full }.text as android.text.Spanned
            text.getSpans(0, text.length, com.codex.remote.ui.screens.RevealTextSpan::class.java).forEach {
                val paint = android.text.TextPaint(); it.updateDrawState(paint); assertEquals(255, paint.alpha)
            }
        }
        composeRule.onNodeWithTag("reply-copy-answer").performClick()
        composeRule.runOnIdle {
            val clipboard = composeRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("I will read it.\n\n$full", clipboard.primaryClip!!.getItemAt(0).coerceToText(composeRule.activity).toString())
        }
    }

    @Test fun openingCommandOutputAndReceivingMoreDoesNotPullTheReaderToTheBottom() {
        val output = (1..90).joinToString("\n") { "QA command line $it" }
        val state = mutableStateOf(baseState(timeline = listOf(
            TimelineItem("q", TimelineKind.USER, body = "Read files", turnId = "t1"),
            TimelineItem("intro", TimelineKind.AGENT, body = "I will read the files.", status = "completed", turnId = "t1"),
            TimelineItem("cmd", TimelineKind.COMMAND, title = "QA command", body = output, status = "completed", turnId = "t1"),
        )).copy(isTurnRunning = true, activeTurnId = "t1"))
        show(state)
        composeRule.onNodeWithTag("timeline-tool-cmd").assertIsDisplayed()
        val before = composeRule.onNodeWithTag("timeline-tool-cmd").fetchSemanticsNode().positionInRoot.y
        composeRule.onNodeWithTag("timeline-tool-cmd").performClick()
        composeRule.waitForIdle()
        assertEquals(before, composeRule.onNodeWithTag("timeline-tool-cmd").fetchSemanticsNode().positionInRoot.y, 3f)
        composeRule.runOnUiThread { state.value = state.value.copy(timeline = state.value.timeline.map { if (it.id == "cmd") it.copy(body = output + "\nMore output") else it }) }
        composeRule.waitForIdle()
        assertEquals(before, composeRule.onNodeWithTag("timeline-tool-cmd").fetchSemanticsNode().positionInRoot.y, 3f)
        composeRule.onNodeWithTag("conversation-bottom").assertIsNotDisplayed()
    }

    @Test fun restoredThreadCombinesPretypedInputWithItsExistingDraft() {
        val state = mutableStateOf(baseState(threads = emptyList()).copy(
            selectedThreadId = null, selectedProjectPath = null,
            connectionStatus = ConnectionStatus.CONNECTING,
        ))
        val callbacks = WorkspaceCallbacks()
        val store = LocalStore(composeRule.activity)
        store.saveDraft(requireNotNull(state.value.activeConnection).id, "restored-thread", "existing draft")
        show(state, callbacks)
        composeRule.onNodeWithTag(COMPOSER_INPUT).performTextInput("typed during connection")
        composeRule.runOnIdle {
            state.value = state.value.copy(
                selectedThreadId = "restored-thread", selectedProjectPath = "/workspace/demo",
                threads = listOf(thread("restored-thread", "Restored thread")),
                connectionStatus = ConnectionStatus.CONNECTED,
            )
        }
        assertEquals("existing draft\ntyped during connection", composerText())
        composeRule.onNodeWithTag(COMPOSER_SEND).assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("existing draft\ntyped during connection" to false), callbacks.sentMessages)
        }
    }

    @Test
    fun completedTurnCollapsesReasoningCommandsToolsAndFileChangesLive() {
        val runningTimeline = listOf(
            TimelineItem("reasoning", TimelineKind.REASONING, title = "Reasoning", body = "private reasoning", status = "inProgress"),
            TimelineItem("cmd-1", TimelineKind.COMMAND, title = "pwd", body = "/workspace/demo", status = "running"),
            TimelineItem("cmd-2", TimelineKind.COMMAND, title = "git status", body = "clean", status = "running"),
            TimelineItem("tool", TimelineKind.TOOL, title = "Remote tool", body = "tool details", status = "started"),
            TimelineItem(
                "files",
                TimelineKind.FILE_CHANGE,
                status = "inProgress",
                fileChanges = listOf(
                    FileChangeSummary(
                        "app/src/Main.kt",
                        "update",
                        "--- a/app/src/Main.kt\n+++ b/app/src/Main.kt\n-old\n+val ready = true",
                    ),
                ),
            ),
        )
        val state = mutableStateOf(baseState(timeline = runningTimeline, isTurnRunning = true))
        show(state)

        scrollTo("timeline-tool-body-reasoning")
        composeRule.onNodeWithText("private reasoning").assertIsDisplayed()
        scrollTo("timeline-tool-body-command-group:cmd-1")
        composeRule.onNodeWithText("运行了多个命令").assertExists()
        composeRule.onNodeWithText("/workspace/demo", substring = true).assertExists()
        scrollTo("timeline-tool-body-tool")
        composeRule.onNodeWithText("tool details").assertIsDisplayed()
        scrollTo("file-changes-files")
        composeRule.onNodeWithText("+val ready = true").assertExists()

        composeRule.runOnIdle {
            state.value = state.value.copy(
                isTurnRunning = false,
                activeTurnId = null,
                timeline = runningTimeline.map { it.copy(status = "completed") },
            )
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("timeline-tool-body-reasoning").fetchSemanticsNodes().isEmpty()
        }

        scrollTo("timeline-tool-reasoning")
        composeRule.onNodeWithText("private reasoning").assertDoesNotExist()
        scrollTo("timeline-tool-command-group:cmd-1")
        composeRule.onNodeWithText("/workspace/demo", substring = true).assertDoesNotExist()
        scrollTo("timeline-tool-tool")
        composeRule.onNodeWithText("tool details").assertDoesNotExist()
        scrollTo("file-changes-files")
        composeRule.onNodeWithText("+val ready = true").assertDoesNotExist()
    }

    @Test
    fun markdownBoldCodeAndLatexRenderAsAndroidSpans() {
        val markdown = """## Rendered heading

**bold-rendered** and inline ${'$'}x^2${'$'}.

```kotlin
val answer = 42
```
""".trimIndent()
        show(mutableStateOf(baseState(timeline = listOf(TimelineItem("markdown", TimelineKind.AGENT, body = markdown)))))
        composeRule.onNodeWithTag("timeline-agent-markdown").assertExists()

        lateinit var rendered: Spanned
        composeRule.runOnIdle {
            val textView = composeRule.activity.window.decorView.descendantTextViews()
                .firstOrNull { it.text.toString().contains("bold-rendered") }
            assertNotNull(textView)
            rendered = textView!!.text as Spanned
        }
        val plainText = rendered.toString()
        val spans = rendered.getSpans(0, rendered.length, Any::class.java)
        assertFalse(plainText.contains("**"))
        assertFalse(plainText.contains("```"))
        assertTrue(plainText.contains("val answer = 42"))
        assertTrue(spans.any { span ->
            (span is StyleSpan && span.style and Typeface.BOLD != 0) ||
                span.javaClass.name.contains("StrongEmphasis", ignoreCase = true)
        })
        assertTrue(spans.any { span ->
            span.javaClass.name.contains("latex", ignoreCase = true) ||
                span.javaClass.name.contains("drawable", ignoreCase = true)
        })
    }

    @Test
    fun bottomButtonReachesSentinelAfterLongAgentResponse() {
        val longAnswer = (1..80).joinToString("\n\n") { "Rendered answer paragraph $it" }
        val timeline = buildList {
            repeat(20) { index -> add(TimelineItem("user-$index", TimelineKind.USER, body = "Question $index")) }
            add(TimelineItem("last-answer", TimelineKind.AGENT, body = longAnswer))
        }
        show(mutableStateOf(baseState(timeline = timeline)))

        composeRule.onNodeWithTag(CONVERSATION_BOTTOM).assertIsDisplayed()
        composeRule.onNodeWithTag(CONVERSATION_LIST).performTouchInput { swipeDown() }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(CONVERSATION_BOTTOM_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(CONVERSATION_BOTTOM_BUTTON).assertIsDisplayed().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(CONVERSATION_BOTTOM_BUTTON).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag(CONVERSATION_BOTTOM).assertIsDisplayed()
    }

    @Test
    fun loadingFiveOlderTurnsKeepsTheVisibleMessageAnchored() {
        val current = (0 until 10).flatMap { index ->
            listOf(
                TimelineItem("new-user-$index", TimelineKind.USER, body = "Current question $index"),
                TimelineItem("new-agent-$index", TimelineKind.AGENT, body = "Current answer $index"),
            )
        }
        val older = (0 until 5).flatMap { index ->
            listOf(
                TimelineItem("old-user-$index", TimelineKind.USER, body = "Older question $index"),
                TimelineItem("old-agent-$index", TimelineKind.AGENT, body = "Older answer $index"),
            )
        }
        val state = mutableStateOf(baseState(timeline = current).copy(hasOlderHistory = true, olderHistoryCursor = "page-2"))
        val callbacks = WorkspaceCallbacks()
        callbacks.onLoadOlder = {
            callbacks.olderLoads++
            state.value = state.value.copy(isOlderHistoryLoading = true)
        }
        show(state, callbacks)

        repeat(12) {
            if (callbacks.olderLoads > 0) return@repeat
            composeRule.onNodeWithTag(CONVERSATION_LIST).performTouchInput { swipeDown() }
            composeRule.waitForIdle()
        }
        assertEquals(1, callbacks.olderLoads)
        val before = composeRule.onNodeWithTag("timeline-item-new-agent-0").fetchSemanticsNode().boundsInRoot.top
        composeRule.runOnIdle {
            state.value = state.value.copy(
                timeline = older + current,
                isOlderHistoryLoading = false,
                hasOlderHistory = false,
                olderHistoryCursor = null,
            )
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("timeline-item-new-agent-0").fetchSemanticsNodes().isNotEmpty()
        }
        val after = composeRule.onNodeWithTag("timeline-item-new-agent-0").fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "History anchor moved from $before to $after (${abs(after - before)} px)",
            abs(after - before) <= 4f,
        )
        composeRule.onNodeWithTag(CONVERSATION_LIST).performScrollToNode(hasTestTag("timeline-item-old-user-0"))
        composeRule.onNodeWithText("Older question 0").assertIsDisplayed()
    }

    @Test
    fun switchingThreadsClearsDraftAndNeverShowsTheOtherTimeline() {
        val threadA = thread("thread-a", "Thread A")
        val threadB = thread("thread-b", "Thread B")
        val state = mutableStateOf(
            baseState(
                timeline = listOf(TimelineItem("a-message", TimelineKind.USER, body = "thread-a-body")),
                threads = listOf(threadA, threadB),
            ),
        )
        show(state)
        composeRule.onNodeWithTag(COMPOSER_INPUT).performTextInput("draft-a")
        composeRule.onNodeWithTag(COMPOSER_ADD).performClick()
        composeRule.onNodeWithText("目标").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(COMPOSER_GOAL_MARKER).assertExists()

        composeRule.runOnIdle {
            state.value = state.value.copy(
                selectedThreadId = "thread-b",
                timeline = (0 until 30).map { index ->
                    TimelineItem("b-message-$index", TimelineKind.USER, body = "thread-b-body-$index")
                },
            )
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(CONVERSATION_BOTTOM).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("thread-b-body-29").assertExists()
        composeRule.onNodeWithText("thread-a-body").assertDoesNotExist()
        composeRule.onAllNodesWithTag(CONVERSATION_BOTTOM_BUTTON).assertCountEquals(0)
        composeRule.onAllNodesWithTag(COMPOSER_GOAL_MARKER).assertCountEquals(0)
        assertFalse(composerText().contains("draft-a"))
        composeRule.runOnIdle {
            assertTrue(state.value.acceptsThreadEvent("thread-b"))
            assertFalse(state.value.acceptsThreadEvent("thread-a"))
            assertFalse(state.value.acceptsThreadEvent(null))
        }
    }

    @Test
    fun addMenuUsesDismissibleGoalMarkerAndStillProvidesPlanSkillsAndPlugins() {
        val state = mutableStateOf(
            baseState().copy(
                skills = listOf(RemoteSkill("qa-check", "QA Skill", "Device test skill", "/skills/qa/SKILL.md", true, setOf("/workspace/demo"))),
                plugins = listOf(RemotePlugin("qa@local", "qa-plugin", "QA Plugin", "Device test plugin", "local", "plugin://qa@local", true)),
            ),
        )
        val callbacks = WorkspaceCallbacks()
        show(state, callbacks)

        composeRule.onNodeWithTag(COMPOSER_ADD).performClick()
        composeRule.onNodeWithText("目标").assertIsDisplayed().performClick()
        composeRule.onAllNodesWithText("Unsupported method", substring = true).assertCountEquals(0)
        composeRule.onNodeWithTag(COMPOSER_GOAL_MARKER).assertIsDisplayed().performClick()
        composeRule.onAllNodesWithTag(COMPOSER_GOAL_MARKER).assertCountEquals(0)

        composeRule.onNodeWithTag(COMPOSER_ADD).performClick()
        composeRule.onNodeWithText("目标").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(COMPOSER_INPUT).performTextInput("ship-device-tests")
        composeRule.onNodeWithTag(COMPOSER_SEND).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("ship-device-tests" to true), callbacks.sentMessages)
        }
        composeRule.onAllNodesWithTag(COMPOSER_GOAL_MARKER).assertCountEquals(0)

        composeRule.onNodeWithTag(COMPOSER_ADD).performClick()
        composeRule.onNodeWithText("目标").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(COMPOSER_ADD).performClick()
        composeRule.onNodeWithText("计划模式").assertIsDisplayed().performClick()
        assertEquals("plan", callbacks.collaborationMode)
        composeRule.onAllNodesWithTag(COMPOSER_GOAL_MARKER).assertCountEquals(0)

        composeRule.onNodeWithTag(COMPOSER_ADD).performClick()
        composeRule.onNodeWithText("QA Skill").performScrollTo().performClick()
        assertTrue(composerText().contains("${'$'}qa-check"))
        composeRule.onNodeWithTag(COMPOSER_ADD).performClick()
        composeRule.onNodeWithText("QA Plugin").performScrollTo().performClick()
        assertTrue(composerText().contains("${'$'}qa-plugin"))
    }

    @Test
    fun goalSlashCommandActivatesTheSameMarkerAndOnlyMarksTheNextSend() {
        val state = mutableStateOf(baseState())
        val callbacks = WorkspaceCallbacks()
        show(state, callbacks)

        composeRule.onNodeWithTag(COMPOSER_INPUT).performTextInput("/goal")
        composeRule.onNodeWithTag(COMPOSER_SEND).performClick()
        composeRule.onNodeWithTag(COMPOSER_GOAL_MARKER).assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(callbacks.sentMessages.isEmpty()) }

        composeRule.onNodeWithTag(COMPOSER_INPUT).performTextInput("finish-device-coverage")
        composeRule.onNodeWithTag(COMPOSER_SEND).performClick()
        composeRule.onAllNodesWithTag(COMPOSER_GOAL_MARKER).assertCountEquals(0)
        assertEquals("", composerText())

        composeRule.onNodeWithTag(COMPOSER_INPUT).performTextInput("normal-follow-up")
        composeRule.onNodeWithTag(COMPOSER_SEND).performClick()
        assertEquals("", composerText())
        composeRule.onNodeWithTag(COMPOSER_INPUT).performTextInput("/goal inline-objective")
        composeRule.onNodeWithTag(COMPOSER_SEND).performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    "finish-device-coverage" to true,
                    "normal-follow-up" to false,
                    "inline-objective" to true,
                ),
                callbacks.sentMessages,
            )
        }
    }

    @Test
    fun goalMarkerWorksBeforeTheRemoteThreadExists() {
        val state = mutableStateOf(baseState(threads = emptyList()).copy(selectedThreadId = null))
        val callbacks = WorkspaceCallbacks()
        show(state, callbacks)

        composeRule.onNodeWithTag(COMPOSER_ADD).performClick()
        composeRule.onNodeWithText("目标").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(COMPOSER_GOAL_MARKER).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_INPUT).performTextInput("create-thread-with-goal")
        composeRule.onNodeWithTag(COMPOSER_SEND).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("create-thread-with-goal" to true), callbacks.sentMessages)
        }
        composeRule.onAllNodesWithText("请先发送第一条消息", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithTag(COMPOSER_GOAL_MARKER).assertCountEquals(0)
    }

    @Test
    fun goalMessagesAreVisiblyDistinguishedFromNormalMessages() {
        val state = mutableStateOf(
            baseState(
                timeline = listOf(
                    TimelineItem("goal-message", TimelineKind.USER, body = "ship it", isGoal = true),
                    TimelineItem("normal-message", TimelineKind.USER, body = "status update"),
                ),
            ),
        )
        show(state)

        composeRule.onNodeWithTag("timeline-goal-goal-message").assertExists()
        composeRule.onNodeWithText("作为目标发送").assertExists()
        composeRule.onAllNodesWithTag("timeline-goal-normal-message").assertCountEquals(0)
    }

    @Test
    fun completedFileChangesUseStructuredExpandablePresentationAndNoChangesTab() {
        val files = TimelineItem(
            id = "files",
            kind = TimelineKind.FILE_CHANGE,
            status = "completed",
            fileChanges = listOf(
                FileChangeSummary("app/src/Main.kt", "update", "--- a/app/src/Main.kt\n+++ b/app/src/Main.kt\n-old\n+new\n+next"),
                FileChangeSummary("app/src/Status.kt", "add", "--- /dev/null\n+++ b/app/src/Status.kt\n+val ready = true"),
            ),
        )
        show(mutableStateOf(baseState(timeline = listOf(files))))

        composeRule.onNodeWithText("已编辑 2 个文件").assertIsDisplayed()
        composeRule.onNodeWithText("--- a/app/src/Main.kt").assertDoesNotExist()
        composeRule.onNodeWithText("已编辑 2 个文件").performClick()
        composeRule.onNodeWithText("app/src/Main.kt").assertExists()
        composeRule.onNodeWithText("app/src/Status.kt").assertExists()
        composeRule.onNodeWithText("Modified").assertExists()
        composeRule.onNodeWithText("Added").assertExists()
        composeRule.onNodeWithText("+2  -1").assertExists()
        composeRule.onAllNodesWithText("Changes").assertCountEquals(0)
        composeRule.onAllNodesWithText("{\"changes\"", substring = true).assertCountEquals(0)
    }

    @Test fun codexTaskListIsAnInnerPageAndBackReturnsToIt() {
        val state = mutableStateOf(baseState())
        show(state, startOnTasksPage = true)
        saveScreenshot("qa-codex-inner-page.png")
        composeRule.onNodeWithText("PROJECTS").assertIsDisplayed()
        composeRule.onNodeWithText("Thread A").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("conversation-header").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("PROJECTS").assertIsDisplayed()
        composeRule.onNodeWithText("Thread A").assertIsDisplayed()
        saveScreenshot("qa-codex-inner-page.png")
    }

    @Test fun liveDurationPrecedesReasoningAndCompletedDetailsCanExpand() {
        val state = mutableStateOf(baseState(timeline = listOf(
            TimelineItem("prompt", TimelineKind.USER, body = "Question", turnId = "t1"),
            TimelineItem("reasoning", TimelineKind.REASONING, title = "Reasoning", body = "Reported summary", status = "inProgress", turnId = "t1")), isTurnRunning = true)
            .copy(activeTurnId = "t1", turnStartedAt = android.os.SystemClock.elapsedRealtime()))
        show(state)
        val duration = composeRule.onNodeWithTag("reply-duration-reasoning").fetchSemanticsNode().boundsInRoot
        val reasoning = composeRule.onNodeWithTag("timeline-tool-reasoning").fetchSemanticsNode().boundsInRoot
        assertTrue(duration.bottom <= reasoning.top)
        composeRule.runOnIdle { state.value = state.value.copy(isTurnRunning = false, activeTurnId = null,
            timeline = listOf(state.value.timeline[0], state.value.timeline[1].copy(status = "completed", elapsedMs = 16_000),
                TimelineItem("final", TimelineKind.AGENT, body = "Answer", status = "completed", turnId = "t1", elapsedMs = 16_000))) }
        composeRule.onNodeWithTag("reply-duration-final").assertIsDisplayed()
        composeRule.onAllNodesWithTag("timeline-tool-reasoning").assertCountEquals(0)
        composeRule.onNodeWithTag("reply-duration-final").performClick()
        composeRule.onNodeWithTag("timeline-tool-reasoning").assertIsDisplayed()
    }

    @Test fun expandingCompletedDetailsKeepsTheFullReplyAndExplainsMissingSummary() {
        val answer = "你好！今天想做点什么？"
        val state = mutableStateOf(baseState(timeline = listOf(
            TimelineItem("prompt", TimelineKind.USER, body = "你好啊", turnId = "t1"),
            TimelineItem("reasoning", TimelineKind.REASONING, title = "Reasoning", status = "completed", turnId = "t1"),
            TimelineItem("final", TimelineKind.AGENT, body = answer, status = "completed", turnId = "t1", elapsedMs = 3000))))
        show(state)
        fun hasFullReply(): Boolean {
            var found = false
            composeRule.runOnUiThread { found = composeRule.activity.window.decorView.descendantTextViews().any {
                it.text.toString() == answer && it.width >= it.paint.measureText(answer) && it.layout != null && it.height >= it.layout.height
            } }
            return found
        }
        composeRule.waitUntil(5_000) { hasFullReply() }
        repeat(3) {
            composeRule.onNodeWithTag("reply-duration-final").performClick()
            composeRule.onNodeWithTag("timeline-tool-reasoning").performClick()
            composeRule.onNodeWithTag("timeline-tool-body-reasoning").assertIsDisplayed()
            composeRule.waitUntil(5_000) { hasFullReply() }
            composeRule.onNodeWithTag("reply-duration-final").performClick()
            composeRule.waitUntil(5_000) { hasFullReply() }
        }
    }

    @Test fun connectionsUseEachLiveHostStateInsteadOfTheSelectionManager() {
        val german = SavedConnection(id = "de", name = "德国", host = "example.com", username = "qa", authType = AuthType.PASSWORD)
        val shenzhen = german.copy(id = "sz", name = "深圳")
        com.codex.remote.ui.UiText.context = null
        val live = mutableStateOf(mapOf("de" to ConnectionStatus.CONNECTED, "sz" to ConnectionStatus.CONNECTING))
        composeRule.setContent { CodexRemoteTheme {
            com.codex.remote.ui.screens.ConnectionsScreen(
                AppUiState(savedConnections = listOf(german, shenzhen), activeConnection = german, connectionStatus = ConnectionStatus.CONNECTING),
                {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, hostStatuses = live.value)
        } }
        composeRule.onAllNodesWithTag("host-connecting-de").assertCountEquals(0)
        composeRule.onNodeWithTag("host-connect-de").assertIsDisplayed()
        composeRule.onNodeWithTag("host-connecting-sz").assertIsDisplayed()
        composeRule.runOnIdle { live.value = live.value + ("sz" to ConnectionStatus.CONNECTED) }
        composeRule.onAllNodesWithTag("host-connecting-sz").assertCountEquals(0)
        composeRule.onNodeWithTag("host-connect-sz").assertIsDisplayed()
    }

    @Test fun aFolderWithoutHistoryCanBeOpenedAndUnreadablePathsCannotBeSelected() {
        val state = mutableStateOf(baseState().copy(projects = emptyList(), threads = emptyList(), selectedProjectPath = null))
        var selected: RemoteProject? = null
        val callbacks = WorkspaceCallbacks().apply {
            onSelectProject = { selected = it }
            onLoadDirectory = { path -> state.value = state.value.copy(remoteDirectoryPath = path,
                remoteDirectoryEntries = listOf(com.codex.remote.domain.RemotePathEntry("new-project", true, false)),
                isRemoteDirectoryLoading = false, remoteDirectoryError = if (path == "/denied") "Permission denied" else null) }
        }
        show(state, callbacks, startOnTasksPage = true)
        composeRule.onNodeWithTag("open-remote-project").performClick()
        composeRule.onNodeWithTag("remote-project-path").performTextClearance()
        composeRule.onNodeWithTag("remote-project-path").performTextInput("/denied")
        composeRule.onNodeWithText("Open folder").performClick()
        composeRule.onNodeWithTag("select-remote-folder").assertIsNotEnabled()
        composeRule.onNodeWithTag("remote-project-path").performTextClearance()
        composeRule.onNodeWithTag("remote-project-path").performTextInput("/srv")
        composeRule.onNodeWithText("Open folder").performClick()
        composeRule.onNodeWithText("new-project").performClick()
        composeRule.onNodeWithTag("select-remote-folder").performClick()
        assertEquals("/srv/new-project", selected?.path)
        assertTrue(selected!!.threads.isEmpty())
    }

    @Test fun switchingExistingProjectsDoesNotBrowseOrCreateFolders() {
        val first = baseState().projects.single()
        val second = first.copy(id = "/workspace/other", name = "other", path = "/workspace/other", threads = emptyList())
        val state = mutableStateOf(baseState().copy(selectedThreadId = null, projects = listOf(first, second)))
        var browsed = false
        val callbacks = WorkspaceCallbacks().apply {
            onLoadDirectory = { browsed = true }
            onSelectProject = { project -> state.value = state.value.copy(selectedProjectPath = project.path) }
        }
        show(state, callbacks)
        composeRule.onNodeWithTag("new-conversation-project").performClick()
        composeRule.onNodeWithTag("switch-project-${second.path}").performClick()
        assertEquals(second.path, state.value.selectedProjectPath)
        assertFalse(browsed)
        composeRule.onNodeWithTag("new-conversation-project").performClick()
        composeRule.onNodeWithTag("switch-project-${first.path}").performClick()
        assertEquals(first.path, state.value.selectedProjectPath)
        assertFalse(browsed)
    }

    @Test fun aNewConversationCanSwitchProjectAndCreateAFolder() {
        val state = mutableStateOf(baseState().copy(selectedThreadId = null))
        val callbacks = WorkspaceCallbacks().apply {
            onLoadDirectory = { path -> state.value = state.value.copy(remoteDirectoryPath = path, remoteDirectoryEntries = emptyList(), remoteDirectoryError = null) }
            onCreateDirectory = { parent, name ->
                assertEquals("/workspace/demo", parent)
                assertEquals("新项目", name)
                state.value = state.value.copy(remoteDirectoryPath = "$parent/$name")
            }
            onSelectProject = { project -> state.value = state.value.copy(projects = listOf(project), selectedProjectPath = project.path) }
        }
        show(state, callbacks)
        composeRule.onNodeWithTag("new-conversation-project").performClick()
        composeRule.onNodeWithTag("choose-other-project-folder").performClick()
        composeRule.onNodeWithTag("new-remote-folder").performClick()
        composeRule.onNodeWithTag("remote-folder-name").performTextInput("../escape")
        composeRule.onNodeWithTag("create-remote-folder").assertIsNotEnabled()
        composeRule.onNodeWithTag("remote-folder-name").performTextClearance()
        composeRule.onNodeWithTag("remote-folder-name").performTextInput("新项目")
        composeRule.onNodeWithTag("create-remote-folder").performClick()
        composeRule.onNodeWithTag("select-remote-folder").performClick()
        composeRule.onNodeWithText("/workspace/demo/新项目").assertIsDisplayed()
    }

    @Test fun expandedSummaryKeepsLongPressSelectionAndCollapsesOnABodyTap() {
        val state = mutableStateOf(baseState(timeline = listOf(
            TimelineItem("prompt", TimelineKind.USER, body = "Question", turnId = "t1"),
            TimelineItem("reasoning", TimelineKind.REASONING, title = "Reasoning", body = "Reported summary can be selected and copied.", status = "completed", turnId = "t1"),
            TimelineItem("final", TimelineKind.AGENT, body = "Answer", status = "completed", turnId = "t1", elapsedMs = 3000))))
        show(state)
        composeRule.onNodeWithTag("reply-duration-final").performClick()
        composeRule.onNodeWithTag("timeline-tool-reasoning").performClick()
        composeRule.onNodeWithTag("timeline-tool-body-reasoning").performTouchInput { longClick(center) }
        composeRule.onNodeWithTag("timeline-tool-body-reasoning").assertIsDisplayed()
        // Dismiss the selection toolbar, then tap the body to close it.
        composeRule.onNodeWithTag("timeline-tool-body-reasoning").performTouchInput { click(center) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("timeline-tool-body-reasoning").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("timeline-tool-reasoning").performClick()
        composeRule.onNodeWithTag("timeline-tool-body-reasoning").assertIsDisplayed()
        composeRule.onNodeWithTag("timeline-tool-body-reasoning").performTouchInput { click(center) }
        composeRule.onAllNodesWithTag("timeline-tool-body-reasoning").assertCountEquals(0)
        composeRule.runOnIdle { state.value = state.value.copy(timeline = state.value.timeline.map { item ->
            if (item.id == "reasoning") item.copy(body = "A long reported summary that scrolls.\n".repeat(100)) else item
        }) }
        composeRule.onNodeWithTag("timeline-tool-reasoning").performClick()
        composeRule.onNodeWithTag(CONVERSATION_LIST).performTouchInput { swipeUp() }
        composeRule.onNodeWithTag("timeline-tool-body-reasoning").assertExists()
        composeRule.onNodeWithTag(CONVERSATION_LIST).performTouchInput { click(center) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("timeline-tool-body-reasoning").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun runningDraftActionsAndQueuedCardHaveSeparateTargets() {
        val state=mutableStateOf(baseState().copy(isTurnRunning=true,activeTurnId="t1"))
        val callbacks=WorkspaceCallbacks()
        callbacks.onQueue={text,_,_->state.value=state.value.copy(queueMessages=listOf("q1" to text))}
        show(state,callbacks)
        composeRule.onAllNodesWithTag("send-queue").assertCountEquals(0)
        composeRule.onAllNodesWithTag("send-steer").assertCountEquals(0)
        composeRule.onNodeWithTag(COMPOSER_INPUT).performTextInput("Queued correction")
        composeRule.onNodeWithTag("send-queue").assertIsSelected()
        composeRule.onNodeWithTag(COMPOSER_SEND).performClick()
        composeRule.onAllNodesWithTag("send-queue").assertCountEquals(0)
        composeRule.onNodeWithTag("queued-q1").assertIsDisplayed()
        assertTrue(composeRule.onNodeWithTag("queued-q1").fetchSemanticsNode().boundsInRoot.bottom <= composeRule.onNodeWithTag(COMPOSER_INPUT).fetchSemanticsNode().boundsInRoot.top)
        composeRule.onNodeWithTag("steer-queued-q1").performClick()
        assertEquals("q1",callbacks.steeredQueue)
        assertTrue(callbacks.sentMessages.isEmpty())
    }

    @Test fun activeToolDetailsOnlyExpandAfterManualTap() {
        val state=mutableStateOf(baseState().copy(isTurnRunning=true,activeTurnId="t1",timeline=listOf(
            TimelineItem("prompt",TimelineKind.USER,body="Work",turnId="t1"),
            TimelineItem("command",TimelineKind.COMMAND,title="bash",body="SECRET_DETAIL",status="inProgress",turnId="t1")
        )))
        show(state)
        composeRule.onAllNodesWithTag("timeline-tool-body-command").assertCountEquals(0)
        composeRule.runOnIdle {state.value=state.value.copy(timeline=state.value.timeline.map {if(it.id=="command")it.copy(body="MORE_DETAIL")else it})}
        composeRule.onAllNodesWithTag("timeline-tool-body-command").assertCountEquals(0)
        composeRule.onNodeWithTag("reply-duration-command").performClick()
        composeRule.onAllNodesWithTag("timeline-tool-body-command").assertCountEquals(0)
        composeRule.onNodeWithTag("timeline-tool-command").performClick()
        composeRule.onNodeWithTag("timeline-tool-body-command").assertIsDisplayed()
    }

    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag(CONVERSATION_LIST).performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun composerText(): String = composeRule.onNodeWithTag(COMPOSER_INPUT)
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun keyboardVisible() = androidx.core.view.ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true

    private fun saveScreenshot(name: String) {
        composeRule.waitForIdle()
        // ponytail: native Popup window exit animations need wall-clock time after Compose is idle.
        android.os.SystemClock.sleep(350)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), name).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun show(
        state: MutableState<AppUiState>,
        callbacks: WorkspaceCallbacks = WorkspaceCallbacks(),
        startOnTasksPage: Boolean = false,
    ) {
        // Component-only snapshots keep fallback labels; HomeDeviceTest covers native locales.
        com.codex.remote.ui.UiText.context = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            composeRule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        composeRule.setContent {
            CodexRemoteTheme(darkTheme = false) {
                WorkspaceScreen(
                    state = state.value,
                    startOnTasksPage = startOnTasksPage,
                    onOpenConnections = {},
                    onNewThread = {},
                    onSelectProject = callbacks.onSelectProject,
                    onSelectThread = {},
                    onLoadOlderHistory = callbacks.onLoadOlder,
                    onRenameThread = { _, _ -> },
                    onArchiveThread = {},
                    onLoadArchivedThreads = {},
                    onUnarchiveThread = {},
                    onDeleteArchivedThread = {},
                    onSetThreadPinned = { _, _ -> },
                    onSend = callbacks.onSend,
                    onQueue = callbacks.onQueue,
                    onSteerQueued = {callbacks.steeredQueue=it},
                    onStop = {},
                    onCompactThread = {},
                    onForkThread = {},
                    onStartReview = { _, _ -> },
                    onRunInit = {},
                    onLoadMcpStatus = {},
                    onReloadMcpServers = {},
                    onStartMcpLogin = {},
                    onMcpAuthorizationHandled = {},
                    onSubmitFeedback = { _, _ -> },
                    onSetGoal = callbacks.onSetGoal,
                    onSetGoalStatus = {},
                    onClearGoal = {},
                    onShowStatus = {},
                    onSetModel = {
                        callbacks.model = it
                        state.value = state.value.copy(selectedModel = it)
                    },
                    onSetReasoningEffort = {
                        callbacks.reasoningEffort = it
                        state.value = state.value.copy(selectedReasoningEffort = it)
                    },
                    onSetServiceTier = {
                        callbacks.serviceTier = it
                        state.value = state.value.copy(selectedServiceTier = it)
                    },
                    onSetCollaborationMode = { callbacks.collaborationMode = it },
                    onSetPermissionProfile = {},
                    onSetPermissionMode = { callbacks.permissionMode = it },
                    onLoadRemoteDirectory = callbacks.onLoadDirectory,
                    onCreateRemoteDirectory = callbacks.onCreateDirectory,
                    onClearRemoteDirectory = {},
                    onStartLogin = {},
                    onCancelLogin = {},
                    onApproval = { _, _ -> },
                    onTrustHostKey = {},
                    onRejectHostKey = {},
                    onDismissNotice = {},
                )
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
class RemoteStateDeviceTest {
    @Test
    fun goalAndPlanParamsMatchTheOfficialAppServerProtocol() {
        val goalParams = CodexRpcClient.threadGoalSetParams(
            threadId = "thread-a",
            objective = "ship",
            status = ThreadGoalStatus.ACTIVE,
        )
        assertEquals("thread-a", goalParams["threadId"]?.jsonPrimitive?.content)
        assertEquals("ship", goalParams["objective"]?.jsonPrimitive?.content)
        assertEquals("active", goalParams["status"]?.jsonPrimitive?.content)

        val turnParams = CodexRpcClient.turnStartParams(
            threadId = "thread-a",
            text = "plan request",
            cwd = "/workspace/demo",
            model = "gpt-5.6-sol",
            reasoningEffort = "ultra",
            serviceTier = "fast",
            approvalPolicy = "on-request",
            collaborationMode = RemoteCollaborationMode("Plan", "plan", "gpt-5.6-sol", "ultra"),
            mentions = emptyList(),
        )
        assertEquals("plan", turnParams["collaborationMode"]?.jsonObject?.get("mode")?.jsonPrimitive?.content)
        assertEquals("gpt-5.6-sol", turnParams["model"]?.jsonPrimitive?.content)
        assertEquals("ultra", turnParams["effort"]?.jsonPrimitive?.content)
        assertEquals("fast", turnParams["serviceTier"]?.jsonPrimitive?.content)
    }

    @Test
    fun lastUsedServerPersistsAndIsSelectedForAutomaticRestore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ConnectionStore(context)
        val suffix = UUID.randomUUID().toString()
        val first = store.save(
            com.codex.remote.domain.ConnectionDraft(
                name = "device-first-$suffix",
                host = "127.0.0.1",
                port = "22",
                username = "tester",
                password = "secret",
            ),
        )
        val second = store.save(
            com.codex.remote.domain.ConnectionDraft(
                name = "device-second-$suffix",
                host = "127.0.0.2",
                port = "22",
                username = "tester",
                password = "secret",
            ),
        )
        try {
            store.recordUsed(first.id)
            val persisted = withTimeout(5_000) {
                store.connections.first { connections ->
                    connections.any { it.id == first.id && it.lastUsedAt > 0 } &&
                        connections.any { it.id == second.id }
                }
            }
            assertEquals(first.id, persisted.lastUsedConnectionOrNull()?.id)
        } finally {
            store.delete(first.id)
            store.delete(second.id)
        }
    }
}

private class WorkspaceCallbacks {
    var onQueue: (String,List<ComposerMention>,List<ComposerImageAttachment>) -> Unit = {_,_,_->}
    var steeredQueue:String?=null
    var onCreateDirectory: (String, String) -> Unit = { _, _ -> }
    var onSelectProject: (RemoteProject) -> Unit = {}
    var onLoadDirectory: (String) -> Unit = {}
    var collaborationMode: String? = null
    var permissionMode: PermissionMode? = null
    var model: String? = null
    var reasoningEffort: String? = null
    var serviceTier: String? = "unchanged"
    val sentMessages = mutableListOf<Pair<String, Boolean>>()
    var olderLoads: Int = 0
    var onLoadOlder: () -> Unit = {}
    var onSetGoal: (String) -> Unit = {}
    var onSend: (String, List<ComposerMention>, List<ComposerImageAttachment>, Boolean) -> Unit =
        { text, _, _, asGoal -> sentMessages += text.trim() to asGoal }
}

private fun baseState(
    timeline: List<TimelineItem> = emptyList(),
    isTurnRunning: Boolean = false,
    threads: List<RemoteThread> = listOf(thread("thread-a", "Thread A")),
): AppUiState {
    val connection = SavedConnection(
        id = "device-check-${UUID.randomUUID()}",
        name = "QA Host",
        host = "10.0.2.2",
        username = "tester",
        authType = AuthType.PASSWORD,
        encryptedPassword = "encrypted",
        lastUsedAt = 1,
    )
    val project = RemoteProject(
        id = "/workspace/demo",
        name = "demo",
        path = "/workspace/demo",
        threads = threads,
        updatedAt = 1,
    )
    val model = RemoteModel(
        id = "gpt-5.6-sol",
        displayName = "GPT-5.6-Sol",
        description = "Device test model",
        isDefault = true,
        supportedReasoningEfforts = listOf(ReasoningEffortOption("low", "Lighter"), ReasoningEffortOption("ultra", "Maximum")),
        defaultReasoningEffort = "ultra",
        inputModalities = setOf("text", "image"),
        serviceTiers = listOf(RemoteServiceTier("fast", "Fast", "Priority processing")),
        defaultServiceTier = "fast",
    )
    return AppUiState(
        savedConnections = listOf(connection),
        activeConnection = connection,
        connectionStatus = com.codex.remote.domain.ConnectionStatus.CONNECTED,
        threads = threads,
        projects = listOf(project),
        selectedProjectPath = project.path,
        selectedThreadId = threads.firstOrNull()?.id,
        timeline = timeline,
        models = listOf(model),
        selectedModel = model.id,
        selectedReasoningEffort = "ultra",
        selectedServiceTier = "fast",
        collaborationModes = listOf(
            RemoteCollaborationMode("Default", "default"),
            RemoteCollaborationMode("Plan", "plan", model.id, "ultra"),
        ),
        selectedCollaborationMode = "default",
        selectedPermissionProfile = ":workspace",
        approvalPolicy = "on-request",
        approvalsReviewer = "user",
        remoteAccount = RemoteAccount("chatgpt", "qa@example.invalid", "plus", false),
        threadTokenUsage = RemoteThreadTokenUsage(128_000, 120_000, 8_000, 200_000),
        isTurnRunning = isTurnRunning,
        activeTurnId = if (isTurnRunning) "turn-a" else null,
        isRestoringLastConnection = false,
    )
}

private fun thread(id: String, title: String) = RemoteThread(
    id = id,
    title = title,
    cwd = "/workspace/demo",
    updatedAt = 1,
    status = "idle",
)

private fun goal(objective: String) = ThreadGoal(
    threadId = "thread-a",
    objective = objective,
    status = ThreadGoalStatus.ACTIVE,
    tokenBudget = null,
    tokensUsed = 0,
    timeUsedSeconds = 0,
    createdAt = 1,
    updatedAt = 1,
)

private fun View.descendantTextViews(): List<TextView> = buildList {
    if (this@descendantTextViews is TextView) add(this@descendantTextViews)
    if (this@descendantTextViews is ViewGroup) {
        repeat(childCount) { childIndex -> addAll(getChildAt(childIndex).descendantTextViews()) }
    }
}

private const val CONVERSATION_LIST = "conversation-list"
private const val CONVERSATION_BOTTOM = "conversation-bottom"
private const val CONVERSATION_BOTTOM_BUTTON = "conversation-bottom-button"
private const val COMPOSER_INPUT = "composer-input"
private const val COMPOSER_ADD = "composer-add"
private const val COMPOSER_PERMISSIONS = "composer-permissions"
private const val COMPOSER_CONTEXT = "composer-context"
private const val COMPOSER_MODEL = "composer-model"
private const val MODEL_MENU = "model-configuration-menu"
private const val COMPOSER_SEND = "composer-send"
private const val COMPOSER_GOAL_MARKER = "composer-goal-marker"

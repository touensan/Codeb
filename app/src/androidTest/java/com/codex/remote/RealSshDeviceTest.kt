package com.codex.remote

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codex.remote.data.store.ConnectionStore
import com.codex.remote.domain.ConnectionStatus
import com.codex.remote.domain.PermissionMode
import com.codex.remote.domain.TimelineKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealSshDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    // ponytail: opt-in because this check uses the saved SSH host and model allowance.
    @Test fun savedHostCanSendAndRenderBeforeCompletion() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realSsh") == "true")
        val saved = runBlocking { ConnectionStore(rule.activity).connections.first().single() }
        lateinit var host: AppViewModel
        rule.runOnIdle { host = ViewModelProvider(HostSessions)["host-${saved.id}", AppViewModel::class.java] }
        rule.onNodeWithTag("home-menu").performClick()
        rule.onNodeWithText("Codex").performClick()
        rule.waitUntil(90_000) { host.state.value.connectionStatus == ConnectionStatus.CONNECTED }
        rule.runOnIdle {
            host.newThread()
            host.setPermissionMode(PermissionMode.READ_ONLY)
        }
        val prompt = "Read ai_codex.md. Do not change files or start other tasks. Report the latest record briefly."
        rule.onNodeWithTag("composer-input").performTextClearance()
        rule.onNodeWithTag("composer-input").performTextInput(prompt)
        rule.onNodeWithTag("composer-send").performClick()
        var streamingSeen = false
        rule.waitUntil(90_000) {
            val state = host.state.value
            if (state.isTurnRunning && state.timeline.any { it.kind == TimelineKind.AGENT && it.body.isNotBlank() }) streamingSeen = true
            state.failedSend != null || (state.selectedThreadId != null && !state.isTurnRunning && state.timeline.any { it.kind == TimelineKind.AGENT })
        }
        val result = host.state.value
        assertNull("Send must not fail", result.failedSend)
        assertTrue("Prompt must remain visible", result.timeline.any { it.kind == TimelineKind.USER && it.body == prompt })
        assertTrue("Reply must stream before turn/completed", streamingSeen)
        assertTrue(result.timeline.any { it.kind == TimelineKind.AGENT && it.body.isNotBlank() })
        rule.onNodeWithTag("timeline-agent-${result.timeline.last { it.kind == TimelineKind.AGENT }.id}").assertIsDisplayed()
        val thread = result.threads.single { it.id == result.selectedThreadId }
        rule.runOnIdle { host.newThread() }
        rule.runOnIdle { host.selectThread(thread) }
        rule.waitUntil(90_000) { host.state.value.selectedThreadId == thread.id && !host.state.value.isBusy }
        assertEquals("Reopening must not duplicate the prompt", 1, host.state.value.timeline.count { it.kind == TimelineKind.USER && it.body == prompt })
        println("Real SSH: prompt retained, reply streamed, turn completed, reopened without duplicate")
    }
}

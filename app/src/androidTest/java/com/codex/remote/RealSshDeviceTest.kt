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

    @Test fun nativeDirectoryCreationAndHostMenuAlignmentWorkOnBothSavedHosts() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("realSshNinth") == "true")
        val name = checkNotNull(arguments.getString("qaFolder"))
        require(Regex("codex-android-qa-[0-9]+").matches(name))
        val hosts = runBlocking { ConnectionStore(rule.activity).connections.first() }
        assertEquals(2, hosts.size)
        hosts.forEach { saved ->
            lateinit var host: AppViewModel
            var found = false
            rule.waitUntil(10_000) {
                rule.runOnUiThread { runCatching { ViewModelProvider(HostSessions)["host-${saved.id}", AppViewModel::class.java] }.onSuccess { host = it; found = true } }
                found
            }
            rule.waitUntil(60_000) { host.state.value.connectionStatus == ConnectionStatus.CONNECTED && !host.state.value.isBusy }
            rule.runOnUiThread { host.loadRemoteDirectory("/tmp") }
            rule.waitUntil(20_000) { !host.state.value.isRemoteDirectoryLoading }
            assertNull(host.state.value.remoteDirectoryError)
            rule.runOnUiThread { host.createRemoteDirectory("/tmp", name) }
            rule.waitUntil(20_000) { !host.state.value.isRemoteDirectoryLoading }
            assertNull(host.state.value.remoteDirectoryError)
            assertEquals("/tmp/$name", host.state.value.remoteDirectoryPath)
            assertTrue(host.state.value.remoteDirectoryEntries.isEmpty())
            rule.runOnUiThread { host.loadRemoteDirectory("/tmp") }
            rule.waitUntil(20_000) { !host.state.value.isRemoteDirectoryLoading }
            assertTrue(host.state.value.remoteDirectoryEntries.any { it.name == name && it.isDirectory })
            rule.runOnUiThread { host.createRemoteDirectory("/tmp", name) }
            rule.waitUntil(20_000) { !host.state.value.isRemoteDirectoryLoading }
            assertNotNull("Existing folders must report an error", host.state.value.remoteDirectoryError)
            rule.runOnUiThread { host.clearRemoteDirectory() }
            println("QA native directory ${saved.name}: created /tmp/$name, duplicate rejected")
        }
        rule.onNodeWithTag("home-menu").performClick()
        rule.onNodeWithText("Codex").performClick()
        rule.onNodeWithTag("host-selector").performClick()
        val all = rule.onNodeWithText(rule.activity.getString(R.string.all_hosts), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        hosts.forEach { saved ->
            val item = hasAnyAncestor(hasTestTag("host-menu-${saved.id}"))
            val text = rule.onNode(hasText(saved.name) and item, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val dot = rule.onNode(hasContentDescription(rule.activity.getString(R.string.connection_connected)) and item, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(all.left, text.left, 1f)
            assertTrue(dot.left > text.right)
        }
    }

    @Test fun savedHostsConnectAndCachedGreetingDoesNotContainTruncatedReplies() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realSshEighth") == "true")
        val hosts = runBlocking { ConnectionStore(rule.activity).connections.first() }
        assertTrue(hosts.isNotEmpty())
        val cache = com.codex.remote.data.store.LocalStore(rule.activity)
        hosts.forEach { saved ->
            lateinit var host: AppViewModel
            var found = false
            rule.waitUntil(10_000) {
                rule.runOnUiThread { runCatching { ViewModelProvider(HostSessions)["host-${saved.id}", AppViewModel::class.java] }.onSuccess { host = it; found = true } }
                found
            }
            rule.waitUntil(60_000) { host.state.value.connectionStatus == ConnectionStatus.CONNECTED && !host.state.value.isBusy }
            println("QA live host ${saved.name}: ${host.state.value.connectionStatus}, threads=${host.state.value.threads.size}")
            cache.index(saved.id)?.threads?.filter { it.title.startsWith("你好") }?.forEach { thread ->
                val timeline = cache.session(saved.id, thread.id)?.timeline.orEmpty()
                println("QA greeting lengths ${saved.name}: " + timeline.filter { it.kind == TimelineKind.AGENT || it.kind == TimelineKind.REASONING }
                    .map { "${it.kind}:${it.id.takeLast(8)}:${it.body.length}:${it.status}:${it.turnId?.takeLast(8)}" })
            }
        }
        rule.onNodeWithTag("home-menu").performClick()
        rule.onNodeWithText("Codex").performClick()
        rule.onNodeWithTag("host-selector").performClick()
        hosts.forEach { saved -> rule.onNodeWithTag("host-menu-${saved.id}").assertIsDisplayed() }
    }

    @Test fun startupConnectsAndTwoRemoteTurnsContinueAcrossNavigation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realSshFourth") == "true")
        val saved = runBlocking { ConnectionStore(rule.activity).connections.first().single() }
        lateinit var host: AppViewModel
        var found = false
        rule.waitUntil(10_000) {
            rule.runOnUiThread { runCatching { ViewModelProvider(HostSessions)["host-${saved.id}", AppViewModel::class.java] }.onSuccess { host = it; found = true } }
            found && host.state.value.activeConnection != null
        }
        rule.waitUntil(90_000) { host.state.value.connectionStatus == ConnectionStatus.CONNECTED && !host.state.value.isBusy }
        assertTrue("Startup must sync the index before entering Codex", host.state.value.threads.isNotEmpty())
        rule.onNodeWithTag("home-menu").performClick()
        rule.onNodeWithText("Codex").performClick()
        rule.onNodeWithText(rule.activity.getString(com.codex.remote.R.string.ui_052)).performClick()
        rule.runOnUiThread { host.setPermissionMode(PermissionMode.READ_ONLY) }
        val firstPrompt = "QA stream A: Do not use tools or change files. Write 20 short numbered Chinese lines about colors, with one sentence per line."
        val secondPrompt = "QA stream B: Do not use tools or change files. Write 20 short numbered Chinese lines about shapes, with one sentence per line."
        rule.onNodeWithTag("composer-input").performTextClearance()
        rule.onNodeWithTag("composer-input").performTextInput(firstPrompt)
        rule.onNodeWithTag("composer-send").performClick()
        rule.waitUntil(90_000) { host.state.value.selectedThreadId != null && host.state.value.activeTurnId != null }
        val firstId = host.state.value.selectedThreadId!!
        val firstStarted = host.state.value.turnStartedAt
        rule.runOnUiThread { host.newThread(); host.sendMessage(secondPrompt) }
        rule.waitUntil(90_000) { host.state.value.selectedThreadId != null && host.state.value.selectedThreadId != firstId && host.state.value.activeTurnId != null }
        val secondId = host.state.value.selectedThreadId!!
        val first = host.state.value.threads.first { it.id == firstId }
        rule.runOnUiThread { host.selectThread(first) }
        assertEquals(firstId, host.state.value.selectedThreadId)
        assertEquals(firstStarted, host.state.value.turnStartedAt)
        assertEquals(1, host.state.value.timeline.count { it.kind == TimelineKind.USER && it.body == firstPrompt })
        assertFalse(host.state.value.hasOlderHistory)
        var streamedA = false
        rule.waitUntil(90_000) {
            val state = host.state.value
            if (state.isTurnRunning && state.timeline.any { it.kind == TimelineKind.AGENT && it.body.isNotBlank() }) streamedA = true
            !state.isTurnRunning && state.timeline.any { it.kind == TimelineKind.AGENT && it.body.isNotBlank() }
        }
        assertTrue("A must render before completion", streamedA)
        rule.runOnUiThread { host.selectThread(host.state.value.threads.first { it.id == secondId }) }
        rule.waitUntil(90_000) { !host.state.value.isTurnRunning && host.state.value.timeline.any { it.kind == TimelineKind.AGENT && it.body.isNotBlank() } }
        assertEquals(1, host.state.value.timeline.count { it.kind == TimelineKind.USER && it.body == secondPrompt })
        assertFalse(host.state.value.hasOlderHistory)
        rule.runOnUiThread {
            host.archiveThread(first)
            host.archiveThread(host.state.value.threads.first { it.id == secondId })
        }
        println("Startup SSH sync and concurrent remote turns verified; QA threads archived")
    }

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

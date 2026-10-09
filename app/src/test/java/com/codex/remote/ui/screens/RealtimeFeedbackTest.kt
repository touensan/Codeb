package com.codex.remote.ui.screens

import com.codex.remote.withConversation
import com.codex.remote.data.rpc.remoteDirectoryChildPath
import com.codex.remote.domain.*
import org.junit.Assert.*
import org.junit.Test

class RealtimeFeedbackTest {
    @Test fun newDirectoryNamesStayWithinTheSelectedParentAndOldRepliesHaveNoInventedTime() {
        val child = ::remoteDirectoryChildPath
        assertEquals("/work/新项目", child("/work/", "新项目"))
        assertEquals("C:\\work\\demo", child("C:\\work", "demo"))
        listOf("", ".", "..", "../escape", "a/b", "a\\b", "bad\u0000name", "bad\nname", "a".repeat(256)).forEach {
            assertTrue(it, runCatching { child("/work", it) }.isFailure)
        }
        listOf("NUL.txt", "demo.", "COM1", "bad:dir").forEach { assertTrue(runCatching { child("C:/", it) }.isFailure) }
        assertTrue(runCatching { child("relative", "demo") }.isFailure)
        val json = kotlinx.serialization.json.Json
        val old = json.decodeFromString<com.codex.remote.data.store.LocalMessage>("""{"id":"old","role":"assistant","text":"reply"}""")
        assertNull(old.completedAt)
        val complete = old.copy(completedAt = 123456789L)
        assertEquals(complete, json.decodeFromString<com.codex.remote.data.store.LocalMessage>(json.encodeToString(com.codex.remote.data.store.LocalMessage.serializer(), complete)))
    }
    @Test fun emptyProjectsSurviveRefreshAndEmptyCompletionKeepsTheReportedSummary() {
        val projects = groupThreadsByProject(emptyList(), listOf("/new", "/new/"))
        assertEquals(1, projects.size)
        assertEquals("/new", projects.single().path)
        val state = AppUiState(projects = projects, selectedProjectPath = "/new")
        assertEquals(projects, state.withThreadIndex(emptyList()).projects)
        val summary = TimelineItem("r", TimelineKind.REASONING, body = "Reported summary", status = "inProgress")
        assertEquals(summary.body, summary.copy(body = "", status = "completed").preservingTurnMetadata(summary).body)
    }
    @Test fun cachedOptimisticPromptIsReconciledInEitherDirectionOnlyForItsTurn() {
        val user = TimelineItem("u", TimelineKind.USER, body = "same", turnId = "t")
        val local = user.copy(id = "local-u")
        assertEquals(listOf(user), mergeTimelineHistory(listOf(user, local), listOf(user)))
        assertEquals(listOf(user), mergeTimelineHistory(listOf(local), listOf(user)))
        val repeat = user.copy(id = "u2", turnId = "t2")
        assertEquals(listOf(user, repeat), mergeTimelineHistory(listOf(user), listOf(repeat)))
        val failed = local.copy(status = "sendFailed")
        assertEquals(listOf(user, failed), mergeTimelineHistory(listOf(user, failed), listOf(user)))
        val persisted = user.copy(id = "rollout-u")
        assertEquals(listOf(user), mergeTimelineHistory(listOf(persisted), listOf(user)))
        assertEquals(listOf(user, user.copy(id = "steered")), mergeTimelineHistory(emptyList(), listOf(user, user.copy(id = "steered"))))
    }

    @Test fun switchingRestoresTheRunningTurnWithoutChangingHostOrIndex() {
        val host = SavedConnection(name = "Host", host = "example.com", username = "qa", authType = AuthType.PRIVATE_KEY)
        val first = RemoteThread("a", "A", "/work", 1, "idle")
        val second = first.copy(id = "b", title = "B")
        val current = AppUiState(activeConnection = host, connectionStatus = ConnectionStatus.CONNECTED,
            selectedThreadId = "b", threads = listOf(first, second))
        val background = current.copy(selectedThreadId = "a", timeline = listOf(TimelineItem("u", TimelineKind.USER, body = "A")))
            .withTurnRunning("a", "turn-a", true, 1000, 2000)
            .withStreamDelta("a", "reply-a", "hello", TimelineKind.AGENT)
        val restored = current.withConversation(background)
        assertEquals("turn-a", restored.activeTurnId)
        assertEquals(1000L, restored.turnStartedAt)
        assertEquals("hello", restored.timeline.last().body)
        assertEquals(current.threads, restored.threads)
        assertEquals(host, restored.activeConnection)
        val changedIndex = current.withTurnRunning("a", "turn-a", true, 1000, 2000)
        assertEquals("active", changedIndex.threads.first().status)
        assertFalse(changedIndex.isTurnRunning)
    }

    @Test fun completedMarkupAndMediaAreRecognizedWhileIncompleteFencesAndUnsafeLinksStayText() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle r=\"2\"/></svg>"
        assertEquals("preview", richParts("```svg\n$svg\n```").single().kind)
        assertEquals("preview", richParts(svg).single().kind)
        assertTrue(richParts("```svg\n<svg>").all { it.kind == "markdown" })
        assertEquals(listOf("image/png", "video/mp4", "audio/mpeg", "application/pdf"),
            listOf("p.png", "v.mp4", "a.mp3", "d.pdf").map(::artifactMime))
        assertEquals("artifact", richParts("![picture](https://example.com/p.png)").single().kind)
        assertEquals("artifact", richParts("[file](sandbox:/work/test.md)").single().kind)
        assertTrue(richParts("[bad](javascript:alert)").all { it.kind == "markdown" })
        val doc = previewDocument("<script>fetch('file:///secret')</script><svg/>")
        assertTrue(doc.contains("sandbox=\"allow-scripts\""))
        assertFalse(doc.contains("allow-same-origin"))
        assertTrue(doc.contains("connect-src 'none'"))
        assertTrue(doc.contains("&lt;script&gt;"))
        assertTrue(doc.contains("height:300px"))
        assertFalse(doc.contains("100vh"))
    }
}

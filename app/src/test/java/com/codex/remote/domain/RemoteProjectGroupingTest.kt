package com.codex.remote.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteProjectGroupingTest {
    @Test
    fun groupsEveryThreadByNormalizedRemoteCwd() {
        val projects = groupThreadsByProject(
            listOf(
                thread("demo-old", "/srv/demo/", 10),
                thread("api", "/srv/api", 20),
                thread("demo-new", "/srv/demo", 30),
            ),
        )

        assertEquals(listOf("demo", "api"), projects.map { it.name })
        assertEquals(listOf("demo-new", "demo-old"), projects[0].threads.map { it.id })
        assertEquals("/srv/demo", projects[0].path)
    }

    @Test
    fun treatsWindowsPathCaseAndSeparatorsAsTheSameProject() {
        val projects = groupThreadsByProject(
            listOf(
                thread("one", "C:\\Work\\Codex", 10),
                thread("two", "c:/work/codex/", 20),
            ),
        )

        assertEquals(1, projects.size)
        assertEquals("codex", projects.single().name)
        assertEquals(2, projects.single().threads.size)
    }

    private fun thread(id: String, cwd: String, updatedAt: Long) = RemoteThread(
        id = id,
        title = id,
        cwd = cwd,
        updatedAt = updatedAt,
        status = "idle",
    )

    @Test fun missingIndexEntryDoesNotDropOpenedThreadOrLiveDeltas() {
        val opened = thread("new", "/srv/demo", 10)
        val user = TimelineItem("local-prompt", TimelineKind.USER, body = "hello")
        val state = AppUiState(threads = listOf(opened), selectedThreadId = opened.id,
            selectedProjectPath = opened.cwd, timeline = listOf(user), isTurnRunning = true, activeTurnId = "turn-new")
        val refreshed = state.withThreadIndex(emptyList())
        assertEquals(opened, refreshed.threads.single())
        assertEquals("new", refreshed.selectedThreadId)
        assertEquals(listOf(user), refreshed.timeline)
        val streaming = refreshed.withStreamDelta("new", "reply", "First ", TimelineKind.AGENT)
            .withStreamDelta("new", "reply", "second", TimelineKind.AGENT)
        assertEquals("First second", streaming.timeline.last().body)
        assertEquals("inProgress", streaming.timeline.last().status)
        assertEquals(true, streaming.isTurnRunning)
        assertEquals(streaming, streaming.withStreamDelta("other", "reply", "wrong thread", TimelineKind.AGENT))
        assertEquals(streaming, streaming.withStreamDelta("new", "", "bad id", TimelineKind.AGENT))
    }

    @Test fun cacheReplacesAcknowledgedPromptWithoutDeletingRepeatedOrFailedMessages() {
        val local = TimelineItem("local-prompt", TimelineKind.USER, body = "hello")
        val remote = local.copy(id = "remote-prompt")
        val reply = TimelineItem("reply", TimelineKind.AGENT, body = "world")
        assertEquals(listOf(remote, reply), mergeTimelineHistory(listOf(local, reply), listOf(remote, reply)))
        assertEquals(listOf(remote, local), mergeTimelineHistory(listOf(remote, local), listOf(remote)))
        assertEquals(listOf(local, remote), mergeTimelineHistory(listOf(local, local.copy(id = "local-second")), listOf(remote)))
        assertEquals(listOf(remote, remote.copy(id = "second")), mergeTimelineHistory(listOf(remote), listOf(remote.copy(id = "second"))))
        val failed = local.copy(status = "sendFailed")
        assertEquals(listOf(failed, remote), mergeTimelineHistory(listOf(failed), listOf(remote)))
    }
}

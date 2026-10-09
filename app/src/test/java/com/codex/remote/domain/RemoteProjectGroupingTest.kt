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

    @Test fun savedComposerDefaultsUseOnlyReturnedCapabilitiesAndKeepStandardSpeed() {
        val model = RemoteModel("m", "Model", "", true,
            supportedReasoningEfforts = listOf(ReasoningEffortOption("low"), ReasoningEffortOption("high")),
            defaultReasoningEffort = "low", serviceTiers = listOf(RemoteServiceTier("fast", "Fast")), defaultServiceTier = "fast")
        val selected = ComposerDefaults("m", "high", "fast", isConfigured = true)
        assertEquals(selected, selected.forModels(listOf(model)))
        assertEquals(null, selected.copy(serviceTier = null).forModels(listOf(model)).serviceTier)
        assertEquals("low", selected.copy(reasoningEffort = "ultra").forModels(listOf(model)).reasoningEffort)
        val unknown = model.copy(supportedReasoningEfforts = emptyList(), serviceTiers = emptyList())
        assertEquals(null, selected.forModels(listOf(unknown)).reasoningEffort)
        assertEquals(null, selected.forModels(listOf(unknown)).serviceTier)
        assertEquals("m", selected.copy(model = "missing").forModels(listOf(model)).model)
    }

    @Test fun completionMetadataBelongsOnlyToCurrentTurnAndSurvivesHistoryRefresh() {
        val old = TimelineItem("old", TimelineKind.AGENT, body = "older", turnId = "t1", elapsedMs = 25, completedAt = 1000)
        val prompt = TimelineItem("user", TimelineKind.USER, body = "new")
        val state = AppUiState(selectedThreadId = "thread", isTurnRunning = true, turnStartedAt = 2000,
            timeline = listOf(old, prompt))
        val running = state.withTurnRunning("thread", "t2", true, 2100, 10_000)
            .withStreamDelta("thread", "answer", "new reply", TimelineKind.AGENT)
        val completed = running.withTurnRunning("thread", "t2", false, 3500, 11_000)
        assertEquals(old, completed.timeline.first())
        assertEquals("t2", completed.timeline.last().turnId)
        assertEquals(1500L, completed.timeline.last().elapsedMs)
        assertEquals(11_000L, completed.timeline.last().completedAt)
        assertEquals(0L, completed.turnStartedAt)
        val serverItem = completed.timeline.last().copy(elapsedMs = null, completedAt = null)
        assertEquals(completed.timeline.last(), mergeTimelineHistory(completed.timeline, listOf(serverItem)).last())
        assertEquals(running, running.withTurnRunning("thread", "t1", false, 3500, 11_000))
    }

    @Test fun resumedRunningTurnDoesNotInventDurationButUsesReportedTiming() {
        val reply = TimelineItem("answer", TimelineKind.AGENT, turnId = "turn", status = "inProgress")
        val state = AppUiState(selectedThreadId = "thread", isTurnRunning = true, activeTurnId = "turn", timeline = listOf(reply))
        val completed = state.withTurnRunning("thread", "turn", false, 9000, 12_000)
        assertEquals(null, completed.timeline.single().elapsedMs)
        assertEquals(12_000L, completed.timeline.single().completedAt)
        val serverCompleted = state.withTurnRunning("thread", "turn", false, 9000, 12_000, 51_000, 10_000)
        assertEquals(51_000L, serverCompleted.timeline.single().elapsedMs)
        assertEquals(10_000L, serverCompleted.timeline.single().completedAt)
        val idleHistory = state.copy(isTurnRunning = false, activeTurnId = null)
        assertEquals(idleHistory, idleHistory.withTurnRunning("thread", "turn", false, 9000, 12_000))
        val unknownLegacy = listOf(TimelineItem("old-user", TimelineKind.USER), TimelineItem("old-answer", TimelineKind.AGENT))
        val replayed = idleHistory.copy(timeline = listOf(reply) + unknownLegacy)
            .withTurnRunning("thread", "turn", false, 9000, 12_000, 51_000, 10_000)
        assertEquals(unknownLegacy, replayed.timeline.takeLast(2))
        assertEquals(51_000L, replayed.timeline.first().elapsedMs)
    }

    @Test fun steeringPromptDoesNotSeparateOneTurnsCompletionMetadata() {
        val state = AppUiState(selectedThreadId = "thread", isTurnRunning = true, turnStartedAt = 1000, activeTurnId = "turn",
            timeline = listOf(TimelineItem("answer", TimelineKind.AGENT, turnId = "turn"),
                TimelineItem("steer", TimelineKind.USER, turnId = "turn"),
                TimelineItem("files", TimelineKind.FILE_CHANGE, turnId = "turn")))
        val completed = state.withTurnRunning("thread", "turn", false, 3000, 20_000)
        assertEquals(listOf(2000L, 2000L, 2000L), completed.timeline.map { it.elapsedMs })
    }
}

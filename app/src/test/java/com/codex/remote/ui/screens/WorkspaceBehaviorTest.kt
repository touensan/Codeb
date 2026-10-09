package com.codex.remote.ui.screens

import com.codex.remote.domain.PermissionMode
import com.codex.remote.domain.TimelineItem
import com.codex.remote.domain.TimelineKind
import com.codex.remote.domain.FileChangeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceBehaviorTest {
    @Test
    fun replyFooterKeepsFilesWithinTheirTurnAndSupportsLegacyHistory() {
        val firstFile = FileChangeSummary("src/First.kt", "update", "+first")
        val secondFile = FileChangeSummary("src/Second.kt", "update", "+second")
        val rows = conversationPresentation(listOf(
            TimelineItem("user-1", TimelineKind.USER, body = "one", turnId = "t1"),
            TimelineItem("file-1", TimelineKind.FILE_CHANGE, status = "completed", fileChanges = listOf(firstFile), turnId = "t1"),
            TimelineItem("file-1b", TimelineKind.FILE_CHANGE, status = "completed", fileChanges = listOf(firstFile.copy(diff = "+next")), turnId = "t1"),
            TimelineItem("comment", TimelineKind.AGENT, body = "working", turnId = "t1"),
            TimelineItem("steer", TimelineKind.USER, body = "also this", turnId = "t1"),
            TimelineItem("answer-1", TimelineKind.AGENT, body = "done", turnId = "t1", elapsedMs = 20000, completedAt = 123000),
            TimelineItem("user-2", TimelineKind.USER, body = "two"),
            TimelineItem("file-2", TimelineKind.FILE_CHANGE, status = "completed", fileChanges = listOf(secondFile)),
            TimelineItem("answer-2", TimelineKind.AGENT, body = "done again"),
        ))
        assertFalse(rows.any { it.item.kind == TimelineKind.FILE_CHANGE })
        assertTrue(rows.first { it.item.id == "comment" }.footerFiles.isEmpty())
        assertEquals(listOf(firstFile.copy(diff = "+first\n+next")), rows.first { it.item.id == "answer-1" }.footerFiles)
        assertEquals(listOf(secondFile), rows.first { it.item.id == "answer-2" }.footerFiles)
        assertEquals(20000L, rows.first { it.item.id == "answer-1" }.item.elapsedMs)
        assertEquals("Took 51 min 54 s", formatTurnElapsed(3114000))
    }

    @Test
    fun runningAndFailedFileChangesStayVisibleUntilReplyCompletes() {
        val rows = conversationPresentation(listOf(
            TimelineItem("file", TimelineKind.FILE_CHANGE, status = "inProgress",
                fileChanges = listOf(FileChangeSummary("a.kt", "update", "+next")), turnId = "t"),
            TimelineItem("answer", TimelineKind.AGENT, body = "partial", status = "inProgress", turnId = "t"),
        ))
        assertTrue(rows.any { it.item.id == "file" })
        assertTrue(rows.last().footerFiles.isEmpty())
    }

    @Test
    fun remotePathNavigationSupportsPosixAndWindowsHosts() {
        assertEquals("/srv/app/src", appendRemotePath("/srv/app/", "src"))
        assertEquals("C:\\work\\app\\src", appendRemotePath("C:\\work\\app\\", "src"))
        assertEquals("/srv/app", remoteParentPath("/srv/app/src"))
        assertEquals("C:\\work\\app", remoteParentPath("C:\\work\\app\\src"))
        assertNull(remoteParentPath("/"))
        assertNull(remoteParentPath("C:\\"))
    }

    @Test
    fun selectedRemotePathsBecomeReadableComposerReferences() {
        assertEquals("@src/Main.kt", remotePathComposerToken("/srv/app", "/srv/app/src/Main.kt"))
        assertEquals("@\"docs/release notes.md\"", remotePathComposerToken("C:\\work\\app", "C:\\work\\app\\docs\\release notes.md"))
    }

    @Test
    fun goalSlashCommandExtractsOnlyARealGoalPrefix() {
        assertEquals("Ship release", extractGoalSlashObjective("/goal Ship release"))
        assertEquals("Ship release", extractGoalSlashObjective("  /GOAL   Ship release  "))
        assertEquals("", extractGoalSlashObjective("/goal"))
        assertNull(extractGoalSlashObjective("/goalkeeper is not a command"))
        assertNull(extractGoalSlashObjective("explain /goal"))
    }

    @Test
    fun reasoningOnlyAutoExpandsWhileItIsRunning() {
        assertTrue("inProgress".isTimelineItemRunning())
        assertTrue("running".isTimelineItemRunning())
        assertFalse("completed".isTimelineItemRunning())
        assertFalse("failed".isTimelineItemRunning())
    }

    @Test
    fun markdownLatexNormalizationLeavesCodeUntouched() {
        val markdown = """Inline ${'$'}x^2 + y^2${'$'}.
`val raw = "${'$'}notMath${'$'}"`
```kotlin
val block = "${'$'}stillRaw${'$'}"
```
""".trimIndent()

        assertEquals(
            """Inline ${'$'}${'$'}x^2 + y^2${'$'}${'$'}.
`val raw = "${'$'}notMath${'$'}"`
```kotlin
val block = "${'$'}stillRaw${'$'}"
```
""".trimIndent(),
            normalizeLatexMarkdown(markdown),
        )
    }

    @Test
    fun permissionModesDistinguishAutoReviewAndRemoteProfiles() {
        assertEquals(PermissionMode.ASK, permissionModeFor(":workspace", "on-request", "user"))
        assertEquals(PermissionMode.AUTO_REVIEW, permissionModeFor(":workspace", "on-request", "auto_review"))
        assertEquals(PermissionMode.FULL_ACCESS, permissionModeFor(":danger-full-access", "never", "user"))
        assertEquals(PermissionMode.READ_ONLY, permissionModeFor(":read-only", "on-request", "user"))
        assertNull(permissionModeFor("team-policy", "on-request", "user"))
    }

    @Test
    fun structuredDiffCountsIgnoreFileHeaders() {
        assertEquals(2 to 1, diffLineCounts("--- a/app.kt\n+++ b/app.kt\n-old\n+new\n+next"))
    }

    @Test
    fun adjacentCommandsArePresentedAsOneStableExpandableStep() {
        val timeline = listOf(
            TimelineItem("reasoning", TimelineKind.REASONING, body = "thinking"),
            TimelineItem("cmd-1", TimelineKind.COMMAND, title = "pwd", body = "/srv/app", status = "completed"),
            TimelineItem("cmd-2", TimelineKind.COMMAND, title = "git status", body = "clean", status = "completed"),
            TimelineItem("answer", TimelineKind.AGENT, body = "Done"),
        )

        val presentation = groupConsecutiveCommands(timeline)

        assertEquals(3, presentation.size)
        assertEquals("运行了多个命令", presentation[1].item.title)
        assertEquals("command-group:cmd-1", presentation[1].item.id)
        assertEquals(listOf("cmd-1", "cmd-2"), presentation[1].sourceItemIds)
        assertTrue(presentation[1].item.body.contains("1. pwd"))
        assertTrue(presentation[1].item.body.contains("2. git status"))
    }
}

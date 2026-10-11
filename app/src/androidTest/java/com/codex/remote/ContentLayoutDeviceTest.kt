package com.codex.remote

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.codex.remote.ui.screens.RichBody
import com.codex.remote.ui.theme.CodexRemoteTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ContentLayoutDeviceTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    @Test fun streamedTablesKeepRowsAndStableHeightAfterResizingAndCompletion() {
        var body by mutableStateOf("Before\n\n| Name | Purpose | Available |\n| --- | --- | --- |\n")
        var running by mutableStateOf(true)
        var width by mutableStateOf(360.dp)
        rule.setContent {CodexRemoteTheme {Column(Modifier.width(width).verticalScroll(rememberScrollState())) {RichBody(body,streaming=running)}}}
        val rows=(1..6).map {"| Row$it | This long cell wraps without changing the source | Yes |\n"}
        rows.forEach {row->rule.runOnUiThread {body+=row};Thread.sleep(100)}
        rule.runOnUiThread {body+="\nAfter the table";running=false}
        rule.waitUntil(10000){rule.onAllNodesWithText("Row6").fetchSemanticsNodes().size==1}
        rule.onAllNodesWithText("Row6").assertCountEquals(1)
        repeat(3) {
            rule.runOnUiThread {width=280.dp};rule.waitForIdle()
            rule.runOnUiThread {width=360.dp};rule.waitForIdle()
        }
        val height=rule.onNodeWithTag("markdown-table").fetchSemanticsNode().boundsInRoot.height
        assertTrue(height>200)
        Thread.sleep(300);rule.waitForIdle()
        assertEquals(height,rule.onNodeWithTag("markdown-table").fetchSemanticsNode().boundsInRoot.height,0.5f)
        rows.indices.forEach {rule.onAllNodesWithText("Row${it+1}").assertCountEquals(1)}
    }
}

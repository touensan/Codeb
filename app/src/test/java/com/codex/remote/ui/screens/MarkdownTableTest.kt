package com.codex.remote.ui.screens

import org.junit.Assert.*
import org.junit.Test

class MarkdownTableTest {
    @Test fun tablesKeepEveryCellAndSurroundingText() {
        val parts=markdownTableParts("Before\n\n| Name | Value |\n| :--- | ---: |\n| **A** | `x` |\n| B | escaped \\| pipe |\n\nAfter")
        assertEquals(listOf(listOf("Name","Value"),listOf("A","x"),listOf("B","escaped | pipe")),parts.single {it.rows!=null}.rows)
        assertTrue(parts.first().source.contains("Before"));assertTrue(parts.last().source.contains("After"))
        assertTrue(markdownTableParts("```txt\n| x | y |\n| --- | --- |\n```\n").none {it.rows!=null})
    }
}

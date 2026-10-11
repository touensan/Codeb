package com.codex.remote.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.parser.Parser

internal data class MarkdownTablePart(val source:String,val rows:List<List<String>>?=null)
private val divider=Regex("^ {0,3}\\|?\\s*:?-+:?\\s*(?:\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
internal fun markdownHasTable(text:String)=text.lineSequence().any {divider.matches(it)}
internal fun markdownTableParts(text:String):List<MarkdownTablePart> {
    if(!markdownHasTable(text))return listOf(MarkdownTablePart(text))
    val lines=text.split('\n');val output=mutableListOf<MarkdownTablePart>();var start=0;var i=0;var fence:Char?=null
    val parser=Parser.builder().extensions(listOf(TablesExtension.create())).build()
    fun children(node:Node)=generateSequence(node.firstChild){it.next}
    fun plain(node:Node):String=when(node) {
        is Text -> node.literal
        is Code -> node.literal
        is SoftLineBreak,is HardLineBreak -> "\n"
        else -> children(node).joinToString(""){plain(it)}
    }
    while(i<lines.size-1) {
        val trimmed=lines[i].trimStart()
        if(trimmed.startsWith("```")||trimmed.startsWith("~~~")){val c=trimmed.first();fence=if(fence==c)null else fence ?: c;i++;continue}
        if(fence!=null || !divider.matches(lines[i+1])){i++;continue}
        var end=i+2
        while(end<lines.size&&lines[end].isNotBlank()&&lines[end].contains('|'))end++
        val source=lines.subList(i,end).joinToString("\n")
        val table=parser.parse(source).firstChild as? TableBlock
        if(table==null){i++;continue}
        val rows=children(table).flatMap {children(it)}.filterIsInstance<TableRow>().map {row->
            children(row).map {cell->plain(cell).trimEnd()}.toList()
        }.toList()
        if(i>start)output+=MarkdownTablePart(lines.subList(start,i).joinToString("\n"))
        output+=MarkdownTablePart(source,rows);i=end;start=end
    }
    if(start<lines.size)output+=MarkdownTablePart(lines.subList(start,lines.size).joinToString("\n"))
    return output
}

@Composable internal fun MarkdownTable(rows:List<List<String>>) {
    if(rows.isEmpty())return
    val columns=rows.maxOf {it.size}
    // Native measured rows avoid TableRowSpan's draw-time height changes inside LazyColumn.
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical=8.dp).testTag("markdown-table")) {
        val cellWidth=(maxWidth/columns).coerceAtLeast(112.dp)
        SelectionContainer {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                rows.forEachIndexed {index,row->
                    Row(Modifier.height(IntrinsicSize.Min).background(if(index==0)MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)) {
                        repeat(columns) {column->
                            Text(row.getOrElse(column){""},Modifier.width(cellWidth).fillMaxHeight()
                                .border(0.5.dp,MaterialTheme.colorScheme.outlineVariant).padding(8.dp),
                                style=MaterialTheme.typography.bodyMedium,fontWeight=if(index==0)FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

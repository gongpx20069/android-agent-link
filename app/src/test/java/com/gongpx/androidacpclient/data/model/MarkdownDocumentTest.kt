package com.gongpx.androidacpclient.data.model

import org.junit.Assert.*
import org.junit.Test

class MarkdownDocumentTest {
    @Test fun longCodeAndTablesBecomeBoundedBlocks() {
        val code = "x".repeat(20000)
        val source = "````\n$code\n```\nstill code\n````\n\n| a | b |\n| --- | --- |\n" +
            (1..100).joinToString("\n") { "| $it | value |" }
        val blocks = parseMarkdownDocument(source)
        val codes = blocks.filterIsInstance<MarkdownBlock.Code>()
        assertTrue(codes.all { it.text.length <= 2048 })
        assertTrue(codes.joinToString("") { it.text }.contains(code))
        assertTrue(codes.joinToString("") { it.text }.contains("still code"))
        val tables = blocks.filterIsInstance<MarkdownBlock.Table>()
        assertEquals(100, tables.sumOf { it.table.rows.size })
        assertTrue(tables.all { it.table.rows.size <= 12 })
    }

    @Test fun preservesAnIncompleteFenceAndSupportsCancellation() {
        assertEquals(listOf(MarkdownBlock.Code("one\ntwo")), parseMarkdownDocument("```kotlin\none\ntwo"))
        assertThrows(InterruptedException::class.java) {
            parseMarkdownDocument("line\n".repeat(1000)) { throw InterruptedException() }
        }
    }

    @Test fun unicodeAndCodeNewlinesSurvivePageBoundaries() {
        val code = ("x".repeat(2047) + "\uD83D\uDE00\n").repeat(20)
        val blocks = parseMarkdownDocument("```\n$code```")
        val restored = blocks.filterIsInstance<MarkdownBlock.Code>().joinToString("") { it.text }
        assertEquals(code.trimEnd('\n'), restored)
        assertTrue(blocks.filterIsInstance<MarkdownBlock.Code>().none {
            it.text.firstOrNull()?.isLowSurrogate() == true || it.text.lastOrNull()?.isHighSurrogate() == true
        })
        val text = "x".repeat(4095) + "\uD83D\uDE00" + "tail".repeat(2000)
        val pages = (0 until (text.length + 4095) / 4096).map { detailTextPage(text, it) }
        assertEquals(text, pages.joinToString(""))
        assertTrue(pages.none { it.firstOrNull()?.isLowSurrogate() == true })
    }

    @Test fun longOutputHasBoundedDisplayPages() {
        val pages = markdownPages(parseMarkdownDocument("```\n" + "x".repeat(100000) + "\n```"))
        assertTrue(pages.size > 1)
        assertTrue(pages.all { page -> page.sumOf { (it as MarkdownBlock.Code).text.length } <= 8192 })
        val source = "| " + (1..20).joinToString(" | ") + " |\n| " + (1..20).joinToString(" | ") { "---" } + " |"
        assertTrue(parseMarkdownDocument(source).all { it is MarkdownBlock.Source })
    }
}

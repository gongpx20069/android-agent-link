package com.gongpx.androidacpclient.data.model

sealed interface MarkdownBlock {
    data class Line(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Table(val table: MarkdownTable) : MarkdownBlock
    data class Source(val text: String) : MarkdownBlock
}

/** Small display blocks allow long replies to be paged without laying out the whole message. */
fun parseMarkdownDocument(text: String, checkCancelled: () -> Unit = {}): List<MarkdownBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var fence: Int? = null
    var codeStarted = false
    val code = StringBuilder()
    fun flushCode() {
        if (code.isNotEmpty()) {
            splitDisplayText(code.toString()).forEach { blocks.add(MarkdownBlock.Code(it)) }
            code.clear()
        }
    }
    var index = 0
    while (index < lines.size) {
        checkCancelled()
        val line = lines[index]
        val delimiter = markdownCodeFenceDelimiterLength(line, fence)
        if (delimiter != null) {
            if (fence == null) {
                fence = delimiter
                codeStarted = false
            } else {
                flushCode()
                fence = null
            }
            index++
            continue
        }
        if (fence != null) {
            if (codeStarted) code.append('\n')
            code.append(line)
            codeStarted = true
            if (code.length >= 2048) flushCode()
            index++
            continue
        }
        val table = parseMarkdownTable(lines, index)
        if (table != null) {
            val oversized = table.table.headers.size > 12 ||
                (table.table.headers + table.table.rows.flatten()).any { it.length > 1024 } ||
                (table.table.headers + table.table.rows.flatten()).sumOf { it.length.toLong() } > 8 * 1024
            if (oversized) {
                splitDisplayText(lines.subList(index, index + table.consumedLineCount).joinToString("\n"))
                    .forEach { blocks.add(MarkdownBlock.Source(it)) }
            } else if (table.table.rows.isEmpty()) blocks.add(MarkdownBlock.Table(table.table))
            else table.table.rows.chunked(12).forEach {
                blocks.add(MarkdownBlock.Table(table.table.copy(rows = it)))
            }
            index += table.consumedLineCount
        } else {
            if (line.isEmpty()) blocks.add(MarkdownBlock.Line(""))
            else splitDisplayText(line).forEach { blocks.add(MarkdownBlock.Line(it)) }
            index++
        }
    }
    flushCode()
    return blocks
}

private fun splitDisplayText(text: String): List<String> = buildList {
    var start = 0
    while (start < text.length) {
        var end = minOf(start + 2048, text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        add(text.substring(start, end))
        start = end
    }
}

fun detailTextPage(text: String, page: Int): String {
    var start = (page.toLong() * 4096).coerceIn(0, text.length.toLong()).toInt()
    var end = ((page.toLong() + 1) * 4096).coerceIn(0, text.length.toLong()).toInt()
    if (start in 1 until text.length && text[start - 1].isHighSurrogate() && text[start].isLowSurrogate()) start--
    if (end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return text.substring(start, end)
}

fun markdownPages(blocks: List<MarkdownBlock>): List<List<MarkdownBlock>> {
    val pages = mutableListOf<List<MarkdownBlock>>()
    var page = mutableListOf<MarkdownBlock>()
    var weight = 0
    blocks.forEach { block ->
        val size = when (block) {
            is MarkdownBlock.Line -> block.text.length
            is MarkdownBlock.Code -> block.text.length
            is MarkdownBlock.Source -> block.text.length
            is MarkdownBlock.Table -> (block.table.headers + block.table.rows.flatten()).sumOf { it.length }
        }
        if (page.isNotEmpty() && (weight + size > 8192 || page.size >= 48)) {
            pages.add(page)
            page = mutableListOf()
            weight = 0
        }
        page.add(block)
        weight += size
    }
    if (page.isNotEmpty()) pages.add(page)
    return pages
}

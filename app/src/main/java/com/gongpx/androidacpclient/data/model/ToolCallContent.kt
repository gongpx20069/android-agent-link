package com.gongpx.androidacpclient.data.model

import org.json.JSONArray
import org.json.JSONObject

/** Keeps the original optional fields intact so a delta cannot erase unsent tool data. */
fun JSONObject.toToolActivity(nowMillis: Long, operationId: String? = null): ChatMessage? {
    if (optString("sessionUpdate") !in setOf("tool_call", "tool_call_update")) return null
    val title = toolString("title") ?: toolString("kind") ?: "Tool call"
    val status = toolString("status")
        ?: if (optString("sessionUpdate") == "tool_call") "started" else "updated"
    return ChatMessage(
        role = MessageRole.Agent,
        text = "$title · $status",
        timestampMillis = nowMillis,
        kind = ChatMessageKind.Activity,
        title = title,
        details = toString(2),
        activityId = toolString("toolCallId"),
        operationId = operationId,
    )
}

fun parseToolCallDetails(details: String?): JSONObject? =
    details?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?.takeIf { it.optString("sessionUpdate") in setOf("tool_call", "tool_call_update") }

/** An empty result lets callers retain their raw-details fallback for other activities. */
fun toolActivitySections(details: String?): List<Pair<String, String>> {
    val tool = parseToolCallDetails(details) ?: return emptyList()
    return buildList {
        tool.opt("rawInput").displayJson()?.let { add("Input" to it) }
        val content = when (val value = tool.opt("content")) {
            is JSONArray -> List(value.length()) { value.opt(it) }
            is JSONObject -> listOf(value)
            else -> emptyList()
        }
        val contentSections = content.mapNotNull { entry ->
            val item = entry as? JSONObject
                ?: return@mapNotNull entry.displayJson()?.let { "Content" to it }
            when (item.toolString("type")) {
                "content" -> {
                    val block = item.optJSONObject("content")
                    if (block?.toolString("type") == "text") {
                        block.toolString("text")?.let { "Output" to it }
                    } else entry.displayJson()?.let { "Content" to it }
                }
                "text" -> item.toolString("text")?.let { "Output" to it }
                "diff" -> "Diff" to buildString {
                    item.toolString("path")?.let { appendLine(it) }
                    appendLine("--- Old")
                    appendLine((item.opt("oldText") as? String).orEmpty())
                    appendLine("+++ New")
                    append((item.opt("newText") as? String).orEmpty())
                }
                "terminal" -> item.toolString("terminalId")?.let { "Terminal" to it }
                    ?: entry.displayJson()?.let { "Content" to it }
                else -> entry.displayJson()?.let { "Content" to it }
            }
        }
        addAll(contentSections)
        // Agents often echo the same output in both ACP content and rawOutput.
        tool.opt("rawOutput").displayJson()?.let { rawOutput ->
            if (contentSections.none { it.second == rawOutput }) add("Output" to rawOutput)
        }
        val locations = tool.optJSONArray("locations")
        if (locations != null && locations.length() > 0) {
            val text = List(locations.length()) { index ->
                val location = locations.optJSONObject(index)
                val path = location?.toolString("path")
                if (path == null) locations.opt(index).displayJson().orEmpty()
                else path + (location.opt("line") as? Number)?.let { ":$it" }.orEmpty()
            }.filter { it.isNotBlank() }.joinToString("\n")
            if (text.isNotBlank()) add("Locations" to text)
        }
        tool.toolString("terminalId")?.let { terminalId ->
            if (none { it.first == "Terminal" && it.second == terminalId }) add("Terminal" to terminalId)
        }
    }
}

fun mergeToolActivity(existing: ChatMessage, incoming: ChatMessage): ChatMessage {
    val delta = parseToolCallDetails(incoming.details) ?: return incoming
    val previous = parseToolCallDetails(existing.details) ?: return incoming
    if (existing.kind != ChatMessageKind.Activity || incoming.kind != ChatMessageKind.Activity ||
        existing.activityId != incoming.activityId
    ) return incoming
    val merged = JSONObject(previous.toString())
    // ACP content is a replacement array, not a stream of entries to append.
    delta.keys().forEach { key -> merged.put(key, delta.get(key)) }
    val rendered = requireNotNull(merged.toToolActivity(incoming.timestampMillis))
    val title = if (delta.has("title")) rendered.title else existing.title ?: rendered.title
    val status = merged.toolString("status")
        ?: if (previous.optString("sessionUpdate") == "tool_call") "started" else "updated"
    return incoming.copy(
        text = "$title · $status",
        title = title,
        details = merged.toString(2),
        operationId = incoming.operationId ?: existing.operationId,
    )
}

private fun JSONObject.toolString(key: String): String? =
    (opt(key) as? String)?.takeIf { it.isNotBlank() }

private fun Any?.displayJson(): String? = when (this) {
    null, JSONObject.NULL -> null
    is JSONObject -> toString(2)
    is JSONArray -> toString(2)
    else -> toString().takeIf { it.isNotBlank() }
}

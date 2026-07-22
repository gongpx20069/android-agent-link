package com.gongpx.androidacpclient.data.model

import org.json.JSONArray
import org.json.JSONObject

data class AgentPlan(
    val entries: List<AgentPlanEntry>,
) {
    val completedCount: Int
        get() = entries.count { it.status == AgentPlanEntryStatus.Completed }

    val isComplete: Boolean
        get() = entries.isNotEmpty() && completedCount == entries.size
}

data class AgentPlanEntry(
    val content: String,
    val priority: AgentPlanEntryPriority,
    val status: AgentPlanEntryStatus,
)

enum class AgentPlanEntryPriority {
    High,
    Medium,
    Low,
    Unknown,
}

enum class AgentPlanEntryStatus {
    Pending,
    InProgress,
    Completed,
    Unknown,
}

fun JSONObject.toAgentPlanMessage(nowMillis: Long): ChatMessage? {
    if (optString("sessionUpdate") != "plan") return null
    val entries = optJSONArray("entries") ?: return toMalformedPlanMessage(nowMillis)
    val plan = parseAgentPlan(entries.toString()) ?: return toMalformedPlanMessage(nowMillis)
    return ChatMessage(
        role = MessageRole.Agent,
        text = "${plan.completedCount}/${plan.entries.size}",
        timestampMillis = nowMillis,
        kind = ChatMessageKind.Plan,
        title = "Plan",
        details = entries.toString(),
        activityId = optString("planId").ifBlank { "agent_plan" },
    )
}

fun parseAgentPlan(json: String?): AgentPlan? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val entries = JSONArray(json)
        val parsedEntries = List(entries.length()) { index ->
            entries.optJSONObject(index)?.toAgentPlanEntry()
        }.filterNotNull().filter { it.content.isNotBlank() }
        parsedEntries.takeIf { it.isNotEmpty() }?.let(::AgentPlan)
    }.getOrNull()
}

private fun JSONObject.toMalformedPlanMessage(nowMillis: Long): ChatMessage {
    return ChatMessage(
        role = MessageRole.Agent,
        text = "Plan update",
        timestampMillis = nowMillis,
        kind = ChatMessageKind.Activity,
        title = "Plan",
        details = toString(2),
        activityId = optString("planId").ifBlank { "agent_plan" },
    )
}

private fun JSONObject.toAgentPlanEntry(): AgentPlanEntry {
    return AgentPlanEntry(
        content = (opt("content") as? String).orEmpty(),
        priority = when (optString("priority").normalizedWireValue()) {
            "high" -> AgentPlanEntryPriority.High
            "medium" -> AgentPlanEntryPriority.Medium
            "low" -> AgentPlanEntryPriority.Low
            else -> AgentPlanEntryPriority.Unknown
        },
        status = when (optString("status").normalizedWireValue()) {
            "pending" -> AgentPlanEntryStatus.Pending
            "in_progress", "inprogress" -> AgentPlanEntryStatus.InProgress
            "completed", "complete", "done" -> AgentPlanEntryStatus.Completed
            else -> AgentPlanEntryStatus.Unknown
        },
    )
}

private fun String.normalizedWireValue(): String {
    return trim().lowercase().replace('-', '_')
}

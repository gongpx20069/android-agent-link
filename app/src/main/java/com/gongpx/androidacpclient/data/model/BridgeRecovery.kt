package com.gongpx.androidacpclient.data.model

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

data class HistoryPage(
    val messages: List<ChatMessage>,
    val historyId: String?,
    val nextBefore: Int?,
    val totalMessages: Int,
    val hasMore: Boolean,
    val eventGeneration: String? = null,
    val latestEventId: Int? = null,
)

data class ApprovalDecisionResult(
    val approvalId: String,
    val status: String,
    val resolved: Boolean,
    val decidedAt: Long? = null,
)

enum class BridgeConnectionFailureCategory {
    Authentication,
    Transport,
}

class BridgeConnectionException(
    val category: BridgeConnectionFailureCategory,
    message: String,
    val httpStatus: Int? = null,
) : IOException(message) {
    val authenticationRequired: Boolean
        get() = category == BridgeConnectionFailureCategory.Authentication
}

/** Do not retain the network exception: its URL or response can contain credentials. */
fun bridgeConnectionFailure(httpStatus: Int? = null): BridgeConnectionException =
    when (httpStatus) {
        401 -> BridgeConnectionException(
            BridgeConnectionFailureCategory.Authentication,
            "Bridge authentication expired or was rejected (HTTP 401). Re-pair this machine and refresh its tunnel access.",
            httpStatus,
        )
        403 -> BridgeConnectionException(
            BridgeConnectionFailureCategory.Authentication,
            "Bridge access was denied (HTTP 403). Check tunnel permissions and re-pair this machine.",
            httpStatus,
        )
        else -> BridgeConnectionException(
            BridgeConnectionFailureCategory.Transport,
            "Cannot connect to the bridge. Check the machine, network, and tunnel, then reconnect.",
            httpStatus,
        )
    }

fun JSONObject.toBridgeApprovalRequest(): BridgeApprovalRequest? {
    val approvalId = stringOrNull("approvalId") ?: return null
    return BridgeApprovalRequest(
        approvalId = approvalId,
        action = stringOrNull("action") ?: "tool_permission",
        summary = stringOrNull("summary") ?: "Agent requests permission",
        details = jsonDetails("details"),
        createdAtMillis = longOrNull("createdAt") ?: 0,
        expiresAtMillis = longOrNull("expiresAt"),
    )
}

fun JSONObject.toApprovalSnapshot(): List<BridgeApprovalRequest> {
    val approvals = optJSONArray("approvals")
        ?: throw IOException("Bridge returned an invalid approval snapshot.")
    return List(approvals.length()) { index ->
        approvals.optJSONObject(index)?.toBridgeApprovalRequest()
            ?: throw IOException("Bridge returned an invalid approval snapshot.")
    }
}

fun requireBridgeResult(events: List<JSONObject>, resultType: String): JSONObject {
    if (events.any {
            it.optString("type") == "bridge.error" ||
                (it.optString("type") == "operation.done" && it.optString("status") in setOf("error", "failed"))
        }
    ) throw IOException("Bridge request failed ($resultType). Reconnect and retry.")
    val result = events.firstOrNull { it.optString("type") == resultType }
        ?: throw IOException("Bridge did not return $resultType.")
    if (result.hasBridgeError()) throw IOException("Bridge request failed ($resultType). Refresh and retry.")
    return result
}

fun JSONObject.toApprovalDecisionResult(approvalId: String, decision: String): ApprovalDecisionResult {
    if (hasBridgeError() || stringOrNull("approvalId") != approvalId || opt("resolved") != true) {
        throw IOException("The bridge did not confirm this approval decision. Refresh approvals and retry.")
    }
    val status = stringOrNull("status")
    if (decision !in setOf("approved", "denied") || status != decision) {
        throw IOException("This approval is no longer available or the decision was not applied. Refresh approvals.")
    }
    return ApprovalDecisionResult(
        approvalId = approvalId,
        status = requireNotNull(status),
        resolved = true,
        decidedAt = longOrNull("decidedAt"),
    )
}

fun JSONObject.toHistoryPage(nowMillis: Long = System.currentTimeMillis()): HistoryPage {
    if (hasBridgeError()) throw IOException("Bridge history is unavailable. Reload recent history and retry.")
    val rows = optJSONArray("messages")
        ?: throw IOException("Bridge returned invalid history messages.")
    val messages = List(rows.length()) { index ->
        rows.getJSONObject(index).toHistoryMessage(nowMillis)
    }
    return HistoryPage(
        messages = messages,
        historyId = stringOrNull("historyId"),
        nextBefore = longOrNull("nextBefore")?.toInt(),
        totalMessages = optInt("totalMessages", messages.count { it.kind == ChatMessageKind.Message }),
        hasMore = optBoolean("hasMore", false),
        eventGeneration = stringOrNull("eventGeneration"),
        latestEventId = longOrNull("latestEventId")?.toInt(),
    )
}

private fun JSONObject.toHistoryMessage(nowMillis: Long): ChatMessage {
    val kindName = stringOrNull("kind")?.replace("_", "")
    val details = jsonDetails("details")
    val kind = if (kindName.equals("control", ignoreCase = true)) {
        val update = runCatching { JSONObject(details.orEmpty()) }.getOrNull()
        when {
            update?.optString("sessionUpdate") == "available_commands_update" && update.optJSONArray("availableCommands") != null ->
                ChatMessageKind.CommandUpdate
            update?.optString("sessionUpdate") == "config_option_update" && update.optJSONArray("configOptions") != null ->
                ChatMessageKind.ConfigUpdate
            else -> ChatMessageKind.Activity
        }
    } else {
        ChatMessageKind.entries.firstOrNull { it.name.equals(kindName, ignoreCase = true) }
            ?: ChatMessageKind.Message
    }
    return ChatMessage(
        role = when (optString("role").lowercase()) {
            "user" -> MessageRole.User
            "system" -> MessageRole.System
            else -> MessageRole.Agent
        },
        text = stringOrNull("text").orEmpty(),
        timestampMillis = longOrNull("timestampMillis") ?: nowMillis,
        kind = kind,
        title = stringOrNull("title"),
        details = details,
        activityId = stringOrNull("activityId") ?: stringOrNull("messageId") ?: stringOrNull("historyItemId"),
        operationId = stringOrNull("operationId"),
    )
}

private fun JSONObject.hasBridgeError(): Boolean =
    has("error") && !isNull("error") && opt("error") != false && opt("error") != ""

private fun JSONObject.stringOrNull(key: String): String? =
    (opt(key) as? String)?.takeIf { it.isNotBlank() }

private fun JSONObject.longOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toLong()

private fun JSONObject.jsonDetails(key: String): String? = when (val value = opt(key)) {
    null, JSONObject.NULL -> null
    is String -> value
    is JSONObject -> value.toString(2)
    is JSONArray -> value.toString(2)
    else -> value.toString()
}

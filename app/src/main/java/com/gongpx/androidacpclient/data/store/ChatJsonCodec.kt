package com.gongpx.androidacpclient.data.store

import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.model.ChatMessage
import com.gongpx.androidacpclient.data.model.ChatMessageKind
import com.gongpx.androidacpclient.data.model.MessageRole
import com.gongpx.androidacpclient.data.model.QueuedPrompt
import org.json.JSONArray
import org.json.JSONObject

internal object ChatJsonCodec {
    fun Chat.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("machineId", machineId)
            .put("machineName", machineName)
            .put("workspaceId", workspaceId)
            .put("workspaceName", workspaceName)
            .put("workspacePath", workspacePath)
            .put("agentId", agentId)
            .put("agentName", agentName)
            .put("createdAtMillis", createdAtMillis)
            .put("acpSessionId", acpSessionId)
            .put("acpSessionResumable", acpSessionResumable)
            .put("messages", JSONArray(messages.map { it.toJson() }))
            .put("queuedPrompts", JSONArray(queuedPrompts.map { it.toJson() }))
            .put("lastBridgeEventId", lastBridgeEventId)
            .put("bridgeEventGeneration", bridgeEventGeneration)
            .put("bridgeResyncRequired", bridgeResyncRequired)
            .put("agentStatus", agentStatus)
            .put("lastSyncAtMillis", lastSyncAtMillis)
            .put("connectionError", connectionError)
            .put("historyId", historyId)
            .put("historyNextBefore", historyNextBefore)
            .put("historyHasMore", historyHasMore)
            .put("historyTotalMessages", historyTotalMessages)
            .put("lastNotifiedOperationId", lastNotifiedOperationId)
            .put("timelineId", timelineId)
    }

    fun JSONObject.toChat(): Chat {
        val sessionId = optString("acpSessionId").ifBlank { null }
        return Chat(
            id = getString("id"),
            title = getString("title"),
            machineId = getString("machineId"),
            machineName = getString("machineName"),
            workspaceId = getString("workspaceId"),
            workspaceName = getString("workspaceName"),
            workspacePath = getString("workspacePath"),
            agentId = getString("agentId"),
            agentName = getString("agentName"),
            createdAtMillis = getLong("createdAtMillis"),
            acpSessionId = sessionId,
            acpSessionResumable = optBoolean("acpSessionResumable", sessionId != null),
            messages = optJSONArray("messages").orEmpty().mapJsonObjects { it.toChatMessage() },
            queuedPrompts = optJSONArray("queuedPrompts").orEmpty().mapJsonObjects { it.toQueuedPrompt() },
            lastBridgeEventId = optInt("lastBridgeEventId", 0),
            bridgeEventGeneration = optString("bridgeEventGeneration").ifBlank { null },
            bridgeResyncRequired = optBoolean("bridgeResyncRequired", false),
            agentStatus = optString("agentStatus", "unknown"),
            lastSyncAtMillis = optLong("lastSyncAtMillis", 0),
            connectionError = optString("connectionError").ifBlank { null },
            historyId = optString("historyId").ifBlank { null },
            historyNextBefore = if (has("historyNextBefore") && !isNull("historyNextBefore")) getInt("historyNextBefore") else null,
            historyHasMore = optBoolean("historyHasMore", false),
            historyTotalMessages = optInt("historyTotalMessages", 0),
            lastNotifiedOperationId = optString("lastNotifiedOperationId").ifBlank { null },
            timelineId = optString("timelineId").ifBlank { getString("id") },
        )
    }

    private fun QueuedPrompt.toJson(): JSONObject {
        return JSONObject()
            .put("operationId", operationId)
            .put("text", text)
            .put("createdAtMillis", createdAtMillis)
            .put("removing", removing)
    }

    private fun JSONObject.toQueuedPrompt(): QueuedPrompt {
        return QueuedPrompt(
            operationId = getString("operationId"),
            text = getString("text"),
            createdAtMillis = getLong("createdAtMillis"),
            removing = optBoolean("removing", false),
        )
    }

    fun ChatMessage.toJson(): JSONObject {
        return JSONObject()
            .put("localId", localId)
            .put("role", role.name)
            .put("text", text)
            .put("timestampMillis", timestampMillis)
            .put("kind", kind.name)
            .put("title", title)
            .put("details", details)
            .put("activityId", activityId)
            .put("operationId", operationId)
    }

    fun JSONObject.toChatMessage(): ChatMessage {
        return ChatMessage(
            role = MessageRole.valueOf(getString("role")),
            text = getString("text"),
            timestampMillis = getLong("timestampMillis"),
            kind = runCatching { ChatMessageKind.valueOf(optString("kind")) }.getOrDefault(ChatMessageKind.Message),
            title = optString("title").ifBlank { null },
            details = optString("details").ifBlank { null },
            activityId = optString("activityId").ifBlank { null },
            operationId = optString("operationId").ifBlank { null },
            localId = optString("localId").ifBlank { java.util.UUID.randomUUID().toString() },
        )
    }

    private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

    private inline fun <T> JSONArray.mapJsonObjects(transform: (JSONObject) -> T): List<T> {
        return List(length()) { index -> transform(getJSONObject(index)) }
    }

}

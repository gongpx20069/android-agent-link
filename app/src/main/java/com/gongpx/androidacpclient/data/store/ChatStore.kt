package com.gongpx.androidacpclient.data.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.model.ChatMessage
import com.gongpx.androidacpclient.data.model.ChatMessageKind
import com.gongpx.androidacpclient.data.model.MessageRole
import com.gongpx.androidacpclient.data.model.QueuedPrompt
import org.json.JSONArray
import org.json.JSONObject

class ChatStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "chats",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): List<Chat> {
        val raw = preferences.getString(KEY_CHATS, "[]") ?: "[]"
        val array = JSONArray(raw)
        return List(array.length()) { index -> array.getJSONObject(index).toChat() }
    }

    fun upsert(chat: Chat) {
        val next = load().filterNot { it.id == chat.id } + chat
        preferences.edit().putString(KEY_CHATS, JSONArray(next.map { it.toJson() }).toString()).apply()
    }

    fun remove(chatId: String) {
        replaceAll(load().filterNot { it.id == chatId })
        setUnread(chatId, false)
    }

    fun replaceAll(chats: List<Chat>) {
        preferences.edit().putString(KEY_CHATS, JSONArray(chats.map { it.toJson() }).toString()).apply()
    }

    fun loadUnreadChatIds(): Set<String> {
        return preferences.getStringSet(KEY_UNREAD_CHAT_IDS, emptySet()).orEmpty().toSet()
    }

    fun setUnread(chatId: String, unread: Boolean) {
        val next = loadUnreadChatIds().toMutableSet()
        if (unread) next.add(chatId) else next.remove(chatId)
        preferences.edit().putStringSet(KEY_UNREAD_CHAT_IDS, next).apply()
    }

    private fun Chat.toJson(): JSONObject {
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
            .put("messages", JSONArray(messages.map { it.toJson() }))
            .put("queuedPrompts", JSONArray(queuedPrompts.map { it.toJson() }))
    }

    private fun JSONObject.toChat(): Chat {
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
            acpSessionId = optString("acpSessionId").ifBlank { null },
            messages = optJSONArray("messages").orEmpty().mapJsonObjects { it.toChatMessage() },
            queuedPrompts = optJSONArray("queuedPrompts").orEmpty().mapJsonObjects { it.toQueuedPrompt() },
        )
    }

    private fun QueuedPrompt.toJson(): JSONObject {
        return JSONObject()
            .put("operationId", operationId)
            .put("text", text)
            .put("createdAtMillis", createdAtMillis)
    }

    private fun JSONObject.toQueuedPrompt(): QueuedPrompt {
        return QueuedPrompt(
            operationId = getString("operationId"),
            text = getString("text"),
            createdAtMillis = getLong("createdAtMillis"),
        )
    }

    private fun ChatMessage.toJson(): JSONObject {
        return JSONObject()
            .put("role", role.name)
            .put("text", text)
            .put("timestampMillis", timestampMillis)
            .put("kind", kind.name)
            .put("title", title)
            .put("details", details)
            .put("activityId", activityId)
            .put("operationId", operationId)
    }

    private fun JSONObject.toChatMessage(): ChatMessage {
        return ChatMessage(
            role = MessageRole.valueOf(getString("role")),
            text = getString("text"),
            timestampMillis = getLong("timestampMillis"),
            kind = runCatching { ChatMessageKind.valueOf(optString("kind")) }.getOrDefault(ChatMessageKind.Message),
            title = optString("title").ifBlank { null },
            details = optString("details").ifBlank { null },
            activityId = optString("activityId").ifBlank { null },
            operationId = optString("operationId").ifBlank { null },
        )
    }

    private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

    private inline fun <T> JSONArray.mapJsonObjects(transform: (JSONObject) -> T): List<T> {
        return List(length()) { index -> transform(getJSONObject(index)) }
    }

    private companion object {
        const val KEY_CHATS = "chats"
        const val KEY_UNREAD_CHAT_IDS = "unread_chat_ids"
    }
}

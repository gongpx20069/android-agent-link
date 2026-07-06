package com.gongpx.androidacpclient.data.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.model.ChatMessage
import com.gongpx.androidacpclient.data.model.ChatMessageKind
import com.gongpx.androidacpclient.data.model.MessageRole
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

    fun replaceAll(chats: List<Chat>) {
        preferences.edit().putString(KEY_CHATS, JSONArray(chats.map { it.toJson() }).toString()).apply()
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
            .put("messages", JSONArray(messages.map { it.toJson() }))
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
            messages = optJSONArray("messages").orEmpty().mapJsonObjects { it.toChatMessage() },
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
        )
    }

    private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

    private inline fun <T> JSONArray.mapJsonObjects(transform: (JSONObject) -> T): List<T> {
        return List(length()) { index -> transform(getJSONObject(index)) }
    }

    private companion object {
        const val KEY_CHATS = "chats"
    }
}

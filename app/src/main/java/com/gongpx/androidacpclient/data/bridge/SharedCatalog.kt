package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.model.Machine
import org.json.JSONObject

/** Follow every catalog page before deciding whether a legacy local chat needs registration. */
suspend fun BridgeClient.listSharedChats(machine: Machine): List<JSONObject> =
    kotlinx.coroutines.withTimeout(30_000) {
        val chats = linkedMapOf<String, JSONObject>()
        var offset = 0
        var pages = 0
        while (true) {
            check(++pages <= 100) { "Shared catalog exceeds 100 pages." }
            val result = controlRequest(machine, "chat.list", JSONObject().put("offset", offset).put("limit", 100))
            check(result.optString("status") == "ok") { "Shared catalog is unavailable." }
            val data = result.getJSONObject("data")
            val rows = data.getJSONArray("chats")
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                chats[row.getString("chatId")] = row
            }
            if (!data.optBoolean("hasMore")) break
            val next = data.getInt("nextOffset")
            check(next > offset) { "Shared catalog pagination did not advance." }
            offset = next
        }
        chats.values.toList()
    }

/** Preserve cached history and replay cursors; remote identity/binding is authoritative. */
fun mergeSharedChat(machine: Machine, remote: JSONObject, local: Chat?): Chat {
    val id = remote.getString("chatId")
    require(id.isNotBlank() && id.length <= 256)
    require(local == null || (local.id == id && local.machineId == machine.id)) {
        "Shared chat identity collides with another machine."
    }
    val path = remote.getString("workspacePath")
    require(path.isNotBlank())
    val workspaceId = remote.getString("workspaceId")
    val agentId = remote.getString("agentId")
    val base = local ?: Chat(
        id = id, title = remote.optString("chatTitle", id),
        machineId = machine.id, machineName = machine.displayName,
        workspaceId = workspaceId, workspaceName = workspaceId, workspacePath = path,
        agentId = agentId, agentName = agentId, createdAtMillis = System.currentTimeMillis(),
    )
    return base.copy(
        title = remote.optString("chatTitle", base.title),
        workspaceId = workspaceId, workspacePath = path,
        workspaceName = machine.workspaces.firstOrNull { it.id == workspaceId }?.displayName ?: workspaceId,
        agentId = agentId, agentName = machine.agents.firstOrNull { it.id == agentId }?.displayName ?: agentId,
        acpSessionId = remote.opt("sessionId") as? String,
        acpSessionResumable = remote.optBoolean("sessionResumable"),
        agentStatus = remote.optString("status", base.agentStatus),
    )
}

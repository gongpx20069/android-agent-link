package com.gongpx.androidacpclient.data.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gongpx.androidacpclient.data.model.Approval
import com.gongpx.androidacpclient.data.model.ApprovalStatus
import com.gongpx.androidacpclient.data.model.BridgeApprovalRequest
import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.model.reconcileApprovalSnapshot
import com.gongpx.androidacpclient.data.model.resolve
import org.json.JSONArray
import org.json.JSONObject

class ApprovalStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "approvals",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): List<Approval> {
        val items = JSONArray(preferences.getString("items", "[]"))
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            Approval(
                id = item.getString("id"),
                chatId = item.getString("chatId"),
                chatTitle = item.getString("chatTitle"),
                machineId = item.getString("machineId"),
                machineName = item.getString("machineName"),
                workspacePath = item.getString("workspacePath"),
                action = item.getString("action"),
                summary = item.getString("summary"),
                status = ApprovalStatus.valueOf(item.getString("status")),
                createdAtMillis = item.getLong("createdAtMillis"),
                details = item.optString("details").ifBlank { null },
                expiresAtMillis = item.optionalLong("expiresAtMillis"),
                decidedAtMillis = item.optionalLong("decidedAtMillis"),
                error = item.optString("error").ifBlank { null },
            )
        }
    }

    fun upsert(approval: Approval) = save(load().filterNot { it.id == approval.id } + approval)

    fun remove(id: String) = save(load().filterNot { it.id == id })

    fun reconcile(chat: Chat, requests: List<BridgeApprovalRequest>) =
        save(reconcileApprovalSnapshot(load(), chat, requests, System.currentTimeMillis()))

    fun resolve(id: String, status: String, decidedAt: Long) =
        save(load().map { if (it.id == id) it.resolve(status, decidedAt) else it })

    private fun save(items: List<Approval>) {
        val json = JSONArray(items.map { item ->
            JSONObject()
                .put("id", item.id).put("chatId", item.chatId).put("chatTitle", item.chatTitle)
                .put("machineId", item.machineId).put("machineName", item.machineName)
                .put("workspacePath", item.workspacePath).put("action", item.action)
                .put("summary", item.summary).put("status", item.status.name)
                .put("createdAtMillis", item.createdAtMillis).put("details", item.details)
                .put("expiresAtMillis", item.expiresAtMillis).put("decidedAtMillis", item.decidedAtMillis)
                .put("error", item.error)
        })
        // Commit before advancing the independently stored replay checkpoint.
        check(preferences.edit().putString("items", json.toString()).commit()) {
            "Could not persist approval state."
        }
    }

    private fun JSONObject.optionalLong(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null
}

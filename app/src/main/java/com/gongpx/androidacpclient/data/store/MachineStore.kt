package com.gongpx.androidacpclient.data.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gongpx.androidacpclient.data.model.Agent
import com.gongpx.androidacpclient.data.model.ConnectionState
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.model.Workspace
import com.gongpx.androidacpclient.data.tunnel.TunnelBinding
import org.json.JSONArray
import org.json.JSONObject

class MachineStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "machines",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): List<Machine> {
        val raw = preferences.getString(KEY_MACHINES, "[]") ?: "[]"
        val array = JSONArray(raw)
        return List(array.length()) { index -> array.getJSONObject(index).toMachine() }
    }

    fun upsert(machine: Machine) {
        val next = load().filterNot { it.id == machine.id } + machine
        check(preferences.edit().putString(KEY_MACHINES, JSONArray(next.map { it.toJson() }).toString()).commit()) {
            "Could not persist paired machine credentials."
        }
    }

    fun remove(machineId: String) {
        replaceAll(load().filterNot { it.id == machineId })
    }

    fun replaceAll(machines: List<Machine>) {
        check(preferences.edit().putString(KEY_MACHINES, JSONArray(machines.map { it.toJson() }).toString()).commit()) {
            "Could not persist paired machine credentials."
        }
    }

    private fun Machine.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("displayName", displayName)
            .put("endpoint", endpoint)
            .put("deviceToken", deviceToken)
            .put("bridgeFingerprint", bridgeFingerprint)
            .put("connectionHeaders", JSONObject(connectionHeaders))
            .put("bridgeVersion", bridgeVersion)
            .put("connectionState", connectionState.name)
            .put("workspaces", JSONArray(workspaces.map { it.toJson() }))
            .put("agents", JSONArray(agents.map { it.toJson() }))
            .put("tunnelBinding", tunnelBinding?.toJson())
    }

    private fun JSONObject.toMachine(): Machine {
        return Machine(
            id = getString("id"),
            displayName = getString("displayName"),
            endpoint = getString("endpoint"),
            deviceToken = getString("deviceToken"),
            bridgeFingerprint = getString("bridgeFingerprint"),
            connectionHeaders = optJSONObject("connectionHeaders").toStringMap(),
            bridgeVersion = optString("bridgeVersion").ifBlank { null },
            connectionState = runCatching { ConnectionState.valueOf(optString("connectionState")) }.getOrDefault(ConnectionState.Unknown),
            workspaces = optJSONArray("workspaces").orEmpty().mapJsonObjects { it.toWorkspace() },
            agents = optJSONArray("agents").orEmpty().mapJsonObjects { it.toAgent() },
            tunnelBinding = optJSONObject("tunnelBinding")?.let(TunnelBinding::fromJson),
        )
    }

    private fun Workspace.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("displayName", displayName)
            .put("absolutePath", absolutePath)
    }

    private fun JSONObject.toWorkspace(): Workspace {
        return Workspace(
            id = getString("id"),
            displayName = getString("displayName"),
            absolutePath = getString("absolutePath"),
        )
    }

    private fun Agent.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("displayName", displayName)
            .put("status", status)
    }

    private fun JSONObject.toAgent(): Agent {
        return Agent(
            id = getString("id"),
            displayName = getString("displayName"),
            status = getString("status"),
        )
    }

    private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { key -> getString(key) }
    }

    private inline fun <T> JSONArray.mapJsonObjects(transform: (JSONObject) -> T): List<T> {
        return List(length()) { index -> transform(getJSONObject(index)) }
    }

    private companion object {
        const val KEY_MACHINES = "machines"
    }
}

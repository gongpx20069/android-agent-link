package com.gongpx.androidacpclient.data.tunnel

import java.io.IOException
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

enum class LoginProvider { GitHub, Microsoft }

internal fun requireTunnelIdentity(clusterId: String, tunnelId: String) {
    require(clusterId.matches(Regex("[a-z0-9]{2,20}")))
    require(tunnelId.matches(Regex("[a-zA-Z0-9-]{1,100}")))
}

data class TunnelBinding(
    val provider: LoginProvider,
    val accountId: String,
    val clusterId: String,
    val tunnelId: String,
    val port: Int,
) {
    init {
        require(accountId.isNotBlank())
        requireTunnelIdentity(clusterId, tunnelId)
        require(port in 1..65535)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("provider", provider.name).put("accountId", accountId)
        .put("clusterId", clusterId).put("tunnelId", tunnelId).put("port", port)

    companion object {
        fun fromJson(json: JSONObject) = TunnelBinding(
            LoginProvider.valueOf(json.getString("provider")), json.getString("accountId"),
            json.getString("clusterId"), json.getString("tunnelId"), json.getInt("port"),
        )
    }
}

data class DiscoveredTunnel(val binding: TunnelBinding, val name: String, val endpoint: String)
data class LoginAccount(val provider: LoginProvider, val id: String, val label: String)

// Credential containers intentionally do not implement data-class toString().
class AccountTokens(
    val account: LoginAccount,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long?,
)

class DeviceLogin(
    val provider: LoginProvider,
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAt: Long,
    val intervalSeconds: Int,
)

class TunnelLoginRequired : IOException("Sign in again from Machines to restore account access.")

fun requireRelayEndpoint(value: String): String {
    val uri = URI(value)
    require(uri.scheme == "https" && uri.host?.endsWith(".devtunnels.ms") == true &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        uri.port in setOf(-1, 443) && uri.path in setOf("", "/")) {
        "The tunnel service returned an unsupported forwarding address."
    }
    return "https://${uri.host}"
}

internal fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
internal fun JSONObject.optionalText(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }
internal fun JSONObject.hasLabel(label: String): Boolean =
    optJSONArray("labels")?.let { array -> (0 until array.length()).any { array.optString(it) == label } } == true

fun parseDiscoveredTunnel(json: JSONObject, account: LoginAccount): List<DiscoveredTunnel> {
    if (!json.hasLabel("agentlink")) return emptyList()
    val ports = json.optJSONArray("ports") ?: throw IOException("Discovery response omitted tunnel ports.")
    return ports.objects().filter { it.hasLabel("agentlink") && it.optString("protocol") in setOf("http", "https") }.map { port ->
        val binding = TunnelBinding(account.provider, account.id, json.getString("clusterId"), json.getString("tunnelId"), port.getInt("portNumber"))
        val candidates = port.optJSONArray("portForwardingUris")?.let { array ->
            List(array.length()) { array.getString(it) }
        }.orEmpty() + json.optJSONArray("endpoints")?.objects().orEmpty().mapNotNull { endpoint ->
            endpoint.optionalText("portUriFormat")?.takeIf { it.contains("{port}") }?.replace("{port}", binding.port.toString())
        }
        val endpoint = candidates.firstOrNull { it.startsWith("https://") }
            ?: throw IOException("The tunnel has no HTTPS forwarding address. Start its bridge host and refresh.")
        DiscoveredTunnel(binding, json.optionalText("description") ?: json.optionalText("name") ?: binding.tunnelId, requireRelayEndpoint(endpoint))
    }
}

fun oauthPollingInterval(error: String, interval: Int): Int = when (error) {
    "authorization_pending" -> interval
    "slow_down" -> interval + 5
    else -> throw IOException(when (error) {
        "access_denied", "authorization_declined" -> "Sign-in was declined."
        "expired_token" -> "Sign-in expired. Start again."
        else -> "Sign-in failed. Check the account and app authorization, then try again."
    })
}

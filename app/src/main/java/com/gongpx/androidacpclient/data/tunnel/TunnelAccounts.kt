package com.gongpx.androidacpclient.data.tunnel

import android.content.Context
import com.gongpx.androidacpclient.BuildConfig
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.store.MachineStore
import java.io.IOException
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TunnelAccounts internal constructor(
    private val store: AccountPersistence,
    private val loadMachines: () -> List<Machine>,
    private val getJson: (String, Map<String, String>) -> JSONObject,
    private val postForm: (String, Map<String, String>) -> JSONObject,
    private val postJson: (String, JSONObject, Map<String, String>) -> JSONObject,
    private val microsoftClientId: String = BuildConfig.MICROSOFT_CLIENT_ID,
) {
    private val lock = Any()
    private class RelayAccess(val endpoint: String, val token: String, val expiresAt: Long)
    private val relayCache = mutableMapOf<TunnelBinding, RelayAccess>()

    val microsoftEnabled: Boolean get() = microsoftClientId.isNotBlank()

    fun accounts(): List<LoginAccount> = synchronized(lock) {
        LoginProvider.entries.mapNotNull { store.load(it)?.account }
    }

    fun signOut(provider: LoginProvider) = synchronized(lock) {
        store.remove(provider)
        relayCache.keys.removeAll { it.provider == provider }
    }

    private fun clientId(provider: LoginProvider): String = when (provider) {
        // Published for use by client apps in the official Dev Tunnels SDK contracts.
        LoginProvider.GitHub -> "Iv1.e7b89e013f801f03"
        LoginProvider.Microsoft -> microsoftClientId.ifBlank {
            throw IOException("Microsoft account discovery is unavailable in this build. Use Dev Tunnels QR pairing instead.")
        }
    }

    private fun tokenUrl(provider: LoginProvider): String = when (provider) {
        LoginProvider.GitHub -> "https://github.com/login/oauth/access_token"
        LoginProvider.Microsoft -> "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    }

    suspend fun beginLogin(provider: LoginProvider): DeviceLogin = withContext(Dispatchers.IO) {
        val values = mutableMapOf("client_id" to clientId(provider))
        if (provider == LoginProvider.Microsoft) values["scope"] = MICROSOFT_SCOPE
        val json = postForm(
            if (provider == LoginProvider.GitHub) "https://github.com/login/device/code"
            else "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode",
            values,
        )
        if (json.has("error")) oauthPollingInterval(json.getString("error"), 5)
        val uri = json.getString("verification_uri")
        val parsed = URI(uri)
        require(parsed.scheme == "https" && parsed.rawUserInfo == null && parsed.port == -1 && when (provider) {
            LoginProvider.GitHub -> parsed.host == "github.com" && parsed.path == "/login/device"
            LoginProvider.Microsoft -> parsed.host in setOf(
                "login.microsoft.com",
                "microsoft.com",
                "www.microsoft.com",
                "login.microsoftonline.com",
            )
        }) { "Sign-in returned an unexpected verification address." }
        DeviceLogin(
            provider, json.getString("device_code"), json.getString("user_code"), uri,
            System.currentTimeMillis() + json.getLong("expires_in").coerceIn(1, 1800) * 1000,
            json.optInt("interval", 5).coerceAtLeast(5),
        )
    }

    suspend fun finishLogin(login: DeviceLogin): LoginAccount = withContext(Dispatchers.IO) {
        var interval = login.intervalSeconds
        while (System.currentTimeMillis() < login.expiresAt) {
            delay(interval * 1000L)
            currentCoroutineContext().ensureActive()
            if (System.currentTimeMillis() >= login.expiresAt) break
            val json = postForm(tokenUrl(login.provider), mapOf(
                "client_id" to clientId(login.provider),
                "device_code" to login.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ))
            if (json.has("error")) {
                interval = oauthPollingInterval(json.getString("error"), interval)
                continue
            }
            val access = json.getString("access_token")
            val account = if (login.provider == LoginProvider.GitHub) {
                val user = getJson("https://api.github.com/user", mapOf("Authorization" to "Bearer $access"))
                LoginAccount(login.provider, user.getLong("id").toString(), user.getString("login"))
            } else {
                // Back-channel token response over TLS. Claims identify the local account only;
                // the tunnel service, not this decoding, authorizes every management request.
                val parts = json.getString("id_token").split('.')
                require(parts.size == 3) { "Microsoft returned an invalid account identifier." }
                val identity = JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
                require(identity.getString("aud") == clientId(login.provider)) { "Microsoft returned an unexpected account audience." }
                LoginAccount(
                    login.provider, identity.getString("tid") + ":" + (identity.optionalText("oid") ?: identity.getString("sub")),
                    identity.optionalText("preferred_username") ?: "Microsoft account",
                )
            }
            val tokens = parseTokens(json, account)
            // Check tunnel authorization before describing the account as ready.
            listTunnels(tokens)
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                store.save(tokens)
                relayCache.keys.removeAll { it.provider == login.provider }
            }
            return@withContext account
        }
        throw IOException("Sign-in expired. Start again.")
    }

    private fun parseTokens(json: JSONObject, account: LoginAccount, previousRefresh: String? = null) = AccountTokens(
        account, json.getString("access_token"),
        if (account.provider == LoginProvider.Microsoft) json.optionalText("refresh_token") ?: previousRefresh else null,
        if (json.has("expires_in")) System.currentTimeMillis() + json.getLong("expires_in") * 1000 else null,
    )

    private fun usableTokens(provider: LoginProvider): AccountTokens {
        if (provider == LoginProvider.Microsoft) clientId(provider)
        val tokens = store.load(provider) ?: throw TunnelLoginRequired()
        if (tokens.expiresAt == null || tokens.expiresAt > System.currentTimeMillis() + 120_000) return tokens
        if (provider != LoginProvider.Microsoft || tokens.refreshToken == null) throw TunnelLoginRequired()
        val json = postForm(tokenUrl(provider), mapOf(
            "client_id" to clientId(provider), "grant_type" to "refresh_token",
            "refresh_token" to tokens.refreshToken, "scope" to MICROSOFT_SCOPE,
        ))
        if (json.has("error")) throw TunnelLoginRequired()
        return parseTokens(json, tokens.account, tokens.refreshToken).also(store::save)
    }

    private fun auth(tokens: AccountTokens) = mapOf(
        "Authorization" to "${if (tokens.account.provider == LoginProvider.GitHub) "github" else "Bearer"} ${tokens.accessToken}",
    )

    private fun listTunnels(tokens: AccountTokens): List<DiscoveredTunnel> {
        val result = mutableListOf<DiscoveredTunnel>()
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        pending.add("$GLOBAL/tunnels?api-version=$API_VERSION&global=true&includePorts=true&labels=agentlink")
        while (pending.isNotEmpty()) {
            val url = pending.removeFirst()
            requireManagementUrl(url)
            if (!visited.add(url) || visited.size > 50) throw IOException("Tunnel discovery exceeded its pagination limit.")
            val json = getJson(url, auth(tokens))
            if (json.has("error") && !json.isNull("error")) throw IOException("Tunnel discovery failed. Refresh to retry.")
            json.optionalText("nextLink")?.let(pending::add)
            val rows = listValues(json)
            for (row in rows) {
                if (row.has("error") && !row.isNull("error")) throw IOException("A tunnel region could not be queried. Refresh to retry.")
                if (row.has("tunnelId")) result.addAll(discoverTunnel(row, tokens))
                else {
                    listValues(row).forEach { result.addAll(discoverTunnel(it, tokens)) }
                    row.optionalText("nextLink")?.let(pending::add)
                }
            }
        }
        return result.distinctBy { it.binding }
    }

    private fun discoverTunnel(summary: JSONObject, tokens: AccountTokens): List<DiscoveredTunnel> {
        if (!summary.hasLabel("agentlink")) return emptyList()
        val ports = summary.optJSONArray("ports")
        val hasBridgePort = ports?.objects()?.any {
            it.hasLabel("agentlink") && it.optString("protocol") in setOf("http", "https")
        } == true
        if (hasBridgePort) return parseDiscoveredTunnel(summary, tokens.account)

        // Global discovery can report no ports while the regional tunnel already
        // advertises a running bridge. Confirm against the owning cluster.
        val clusterId = summary.getString("clusterId")
        val tunnelId = summary.getString("tunnelId")
        requireTunnelIdentity(clusterId, tunnelId)
        val detail = getJson(
            "https://$clusterId.rel.tunnels.api.visualstudio.com/tunnels/$tunnelId" +
                "?api-version=$API_VERSION&includePorts=true",
            auth(tokens),
        )
        if (detail.has("error") && !detail.isNull("error")) {
            throw IOException("Tunnel details could not be queried. Refresh to retry.")
        }
        if (detail.optionalText("clusterId") != clusterId ||
            detail.optionalText("tunnelId") != tunnelId
        ) throw IOException("Tunnel identity changed during discovery. Refresh to retry.")
        return parseDiscoveredTunnel(detail, tokens.account)
    }

    private fun listValues(json: JSONObject): List<JSONObject> {
        // The management contract makes value optional for empty lists/regions.
        if (!json.has("value") || json.isNull("value")) return emptyList()
        return json.getJSONArray("value").objects()
    }

    suspend fun discover(provider: LoginProvider): List<DiscoveredTunnel> = withContext(Dispatchers.IO) {
        synchronized(lock) { listTunnels(usableTokens(provider)) }
    }

    private fun access(binding: TunnelBinding, endpoint: String): RelayAccess {
        val tokens = usableTokens(binding.provider)
        if (tokens.account.id != binding.accountId) throw TunnelLoginRequired()
        val cached = relayCache[binding]
        if (cached != null && cached.endpoint == endpoint && cached.expiresAt > System.currentTimeMillis()) return cached
        val base = "https://${binding.clusterId}.rel.tunnels.api.visualstudio.com"
        val url = "$base/tunnels/${binding.tunnelId}?api-version=$API_VERSION&includePorts=true&tokenScopes=connect"
        val json = getJson(url, auth(tokens))
        val matching = parseDiscoveredTunnel(json, tokens.account).firstOrNull { it.binding == binding }
            ?: throw IOException("The saved bridge port is no longer advertised. Refresh Machines.")
        if (matching.endpoint != endpoint) throw IOException("The tunnel address changed. Select the computer again from Machines.")
        val token = json.getJSONObject("accessTokens").getString("connect")
        return RelayAccess(endpoint, token, System.currentTimeMillis() + 5 * 60_000).also { relayCache[binding] = it }
    }

    // Called only on network workers, including OkHttp's asynchronous WebSocket handshake.
    fun relayHeaders(endpoint: String, headers: Map<String, String>): Map<String, String> = synchronized(lock) {
        val origin = endpoint.replaceFirst("wss://", "https://").trimEnd('/')
        val machine = loadMachines().firstOrNull { it.endpoint.replaceFirst("wss://", "https://").trimEnd('/') == origin && it.tunnelBinding != null }
            ?: return@synchronized headers
        val binding = requireNotNull(machine.tunnelBinding)
        val grant = access(binding, requireRelayEndpoint(origin))
        headers.filterKeys { !it.equals("X-Tunnel-Authorization", true) } + ("X-Tunnel-Authorization" to "tunnel ${grant.token}")
    }

    suspend fun pair(tunnel: DiscoveredTunnel, showCode: (String) -> Unit): Machine = withContext(Dispatchers.IO) {
        val headers = synchronized(lock) {
            mapOf("X-Tunnel-Authorization" to "tunnel ${access(tunnel.binding, tunnel.endpoint).token}")
        }
        val health = getJson(tunnel.endpoint + "/health", headers)
        if (!health.optBoolean("accountPairing")) throw IOException("Update the bridge to enable account pairing, or use its QR code.")
        val fingerprint = health.getString("bridgeFingerprint")
        val request = postJson(tunnel.endpoint + "/pairing/request", JSONObject().put("device", JSONObject()
            .put("name", "AgentLink Android").put("platform", "android").put("appVersion", BuildConfig.VERSION_NAME)), headers)
        val code = request.getString("confirmationCode")
        require(code.matches(Regex("[0-9]{6}"))) { "Invalid pairing confirmation." }
        withContext(Dispatchers.Main) { showCode(code) }
        val expiry = minOf(request.getLong("expiresAt"), System.currentTimeMillis() + 120_000)
        val poll = JSONObject().put("requestId", request.getString("requestId")).put("pollToken", request.getString("pollToken"))
        while (System.currentTimeMillis() < expiry) {
            delay(2000)
            val result = postJson(tunnel.endpoint + "/pairing/status", poll, headers)
            when (result.getString("status")) {
                "pending" -> Unit
                "approved" -> {
                    if (result.getString("bridgeFingerprint") != fingerprint || result.getString("machineId") != health.getString("machineId")) {
                        throw IOException("The bridge identity changed during pairing. Retry and verify the computer.")
                    }
                    return@withContext Machine(
                        result.getString("machineId"), health.getString("machineName"),
                        tunnel.endpoint.replaceFirst("https://", "wss://"), result.getString("deviceToken"),
                        fingerprint, tunnelBinding = tunnel.binding,
                    )
                }
                "denied" -> throw IOException("Pairing was denied on the computer.")
                "expired" -> throw IOException("Pairing expired. Try again.")
                else -> throw IOException("The bridge returned an invalid pairing status.")
            }
        }
        throw IOException("Pairing expired. Try again.")
    }

    companion object {
        private const val API_VERSION = "2023-09-27-preview"
        private const val GLOBAL = "https://global.rel.tunnels.api.visualstudio.com"
        // Request delegated consent explicitly; .default relies on preconfigured resource permissions.
        private const val MICROSOFT_SCOPE = "46da2f7e-b5ef-422a-88d4-2a7f9de6a0b2/all openid profile offline_access"
        @Volatile private var instance: TunnelAccounts? = null
        fun get(context: Context): TunnelAccounts = instance ?: synchronized(this) {
            instance ?: run {
                val application = context.applicationContext
                val http = AccountHttp()
                TunnelAccounts(
                    AccountStore(application), MachineStore(application)::load,
                    { url, headers -> http.request(url, headers) }, http::form, http::json,
                )
            }.also { instance = it }
        }

        internal fun requireManagementUrl(value: String) {
            val uri = URI(value)
            require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port in setOf(-1, 443) &&
                uri.host?.matches(Regex("[a-z0-9]+\\.rel\\.tunnels\\.api\\.visualstudio\\.com")) == true &&
                uri.path.startsWith("/tunnels") && uri.fragment == null) { "Invalid tunnel pagination address." }
        }
    }
}

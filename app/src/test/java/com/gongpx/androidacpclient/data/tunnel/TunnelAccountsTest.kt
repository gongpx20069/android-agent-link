package com.gongpx.androidacpclient.data.tunnel

import com.gongpx.androidacpclient.data.model.Machine
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TunnelAccountsTest {
    private val account = LoginAccount(LoginProvider.GitHub, "42", "owner")
    private val binding = TunnelBinding(account.provider, account.id, "usw2", "tunnel-one", 4317)
    private val origin = "https://tunnel-one-4317.usw2.devtunnels.ms"
    private val machine = Machine("machine", "Computer", origin.replaceFirst("https", "wss"), "bridge-device", "fingerprint", tunnelBinding = binding)

    private fun tunnel() = JSONObject("""{
        "clusterId":"usw2","tunnelId":"tunnel-one","labels":["agentlink"],
        "description":"Computer","accessTokens":{"connect":"relay-secret"},
        "ports":[{"portNumber":4317,"protocol":"http","labels":["agentlink"],
          "portForwardingUris":["$origin"]}]
    }""")

    private class MemoryStore(initial: AccountTokens) : AccountPersistence {
        var tokens: AccountTokens? = initial
        override fun load(provider: LoginProvider) = tokens?.takeIf { it.account.provider == provider }
        override fun save(tokens: AccountTokens) { this.tokens = tokens }
        override fun remove(provider: LoginProvider) { tokens = null }
    }

    private fun service(
        store: MemoryStore = MemoryStore(AccountTokens(account, "identity-secret", null, null)),
        paired: Machine = machine,
        get: (String, Map<String, String>) -> JSONObject = { _, _ -> tunnel() },
        form: (String, Map<String, String>) -> JSONObject = { _, _ -> error("Unexpected OAuth request") },
    ) = TunnelAccounts(store, { listOf(paired) }, get, form, { _, _, _ -> error("Unexpected pairing") }, microsoftClientId = "")

    @Test
    fun discoversOnlyLabelledHttpPortsAndRetainsBinding() {
        val computers = parseDiscoveredTunnel(tunnel(), account)
        assertEquals(binding, computers.single().binding)
        assertEquals(origin, computers.single().endpoint)
        val unlabelled = tunnel().put("labels", JSONArray())
        assertTrue(parseDiscoveredTunnel(unlabelled, account).isEmpty())
        val unlabelledPort = tunnel().apply { getJSONArray("ports").getJSONObject(0).remove("labels") }
        assertTrue(parseDiscoveredTunnel(unlabelledPort, account).isEmpty())
    }

    @Test
    fun acceptsServiceProvidedPortFormatWithoutInventingHostname() {
        val json = tunnel().apply {
            getJSONArray("ports").getJSONObject(0).remove("portForwardingUris")
            put("endpoints", JSONArray("""[{"portUriFormat":"https://tunnel-one-{port}.usw2.devtunnels.ms"}]"""))
        }
        assertEquals(origin, parseDiscoveredTunnel(json, account).single().endpoint)
    }

    @Test
    fun rejectsCredentialExfiltrationEndpointsAndPagination() {
        listOf(
            "http://tunnel.devtunnels.ms", "https://devtunnels.ms.evil.example",
            "https://user@host.devtunnels.ms", "$origin?token=secret", "$origin/other",
            "$origin:8443", "https://127.0.0.1",
        ).forEach { assertTrue(it, runCatching { requireRelayEndpoint(it) }.isFailure) }
        listOf(
            "https://evil.example/tunnels", "https://global.rel.tunnels.api.visualstudio.com.evil.example/tunnels",
            "http://global.rel.tunnels.api.visualstudio.com/tunnels", "https://user@global.rel.tunnels.api.visualstudio.com/tunnels",
        ).forEach { assertTrue(it, runCatching { TunnelAccounts.requireManagementUrl(it) }.isFailure) }
    }

    @Test
    fun cannotInjectTunnelIdentifiersIntoManagementRequests() {
        assertTrue(runCatching { binding.copy(clusterId = "evil.example/") }.isFailure)
        assertTrue(runCatching { binding.copy(tunnelId = "../other") }.isFailure)
        assertTrue(runCatching { binding.copy(port = 0) }.isFailure)
        assertEquals(binding, TunnelBinding.fromJson(binding.toJson()))
    }

    @Test
    fun relayReceivesOnlyConnectTokenNotIdentityOrRefreshCredentials() {
        val calls = mutableListOf<String>()
        val service = service(get = { url, headers ->
            calls.add(url)
            assertTrue(url.startsWith("https://usw2.rel.tunnels.api.visualstudio.com/tunnels/tunnel-one"))
            assertEquals("github identity-secret", headers["Authorization"])
            tunnel()
        })
        val headers = service.relayHeaders(origin, mapOf("X-Tunnel-Authorization" to "old", "Other" to "value"))
        assertEquals(mapOf("X-Tunnel-Authorization" to "tunnel relay-secret", "Other" to "value"), headers)
        assertFalse(headers.values.any { it.contains("identity-secret") })
        service.relayHeaders(machine.endpoint, emptyMap())
        assertEquals(1, calls.size)
        val unrelated = mapOf("X-Tunnel-Authorization" to "QR-token")
        assertEquals(unrelated, service.relayHeaders("https://other.devtunnels.ms", unrelated))
        assertEquals(1, calls.size)
    }

    @Test
    fun accountSwitchAndSignOutCannotReuseAnotherAccountsCachedGrant() {
        val store = MemoryStore(AccountTokens(account, "identity-secret", null, null))
        val service = service(store)
        service.relayHeaders(origin, emptyMap())
        store.tokens = AccountTokens(account.copy(id = "different"), "other", null, null)
        assertTrue(runCatching { service.relayHeaders(origin, emptyMap()) }.exceptionOrNull() is TunnelLoginRequired)
        service.signOut(account.provider)
        assertTrue(runCatching { service.relayHeaders(origin, emptyMap()) }.exceptionOrNull() is TunnelLoginRequired)
    }

    @Test
    fun microsoftIdentityRefreshPrecedesTunnelTokenIssuance() {
        val microsoft = LoginAccount(LoginProvider.Microsoft, "tenant:user", "Microsoft")
        val store = MemoryStore(AccountTokens(microsoft, "expired", "refresh-secret", 1))
        var refreshed = false
        val service = TunnelAccounts(store, { listOf(machine.copy(tunnelBinding = binding.copy(provider = microsoft.provider, accountId = microsoft.id))) },
            { _, headers ->
                assertTrue(refreshed)
                assertEquals("Bearer fresh-access", headers["Authorization"])
                tunnel()
            }, { url, body ->
                assertEquals("https://login.microsoftonline.com/common/oauth2/v2.0/token", url)
                assertEquals("refresh_token", body["grant_type"])
                assertEquals("refresh-secret", body["refresh_token"])
                assertFalse(body.containsKey("client_secret"))
                refreshed = true
                JSONObject("""{"access_token":"fresh-access","refresh_token":"rotated","expires_in":3600}""")
            }, { _, _, _ -> error("Unexpected pairing") }, microsoftClientId = "own-client-id")
        assertEquals("tunnel relay-secret", service.relayHeaders(origin, emptyMap())["X-Tunnel-Authorization"])
        assertEquals("rotated", store.tokens?.refreshToken)
    }

    @Test
    fun expiredGithubTokenRequiresLoginRatherThanInventingRefreshWithoutSecret() {
        val service = service(MemoryStore(AccountTokens(account, "expired", null, 1)))
        assertTrue(runCatching { service.relayHeaders(origin, emptyMap()) }.exceptionOrNull() is TunnelLoginRequired)
    }

    @Test
    fun changedEndpointRequiresExplicitUserAction() {
        val service = service(get = { _, _ ->
            tunnel().apply { getJSONArray("ports").getJSONObject(0).put("portForwardingUris", JSONArray("""["https://other.devtunnels.ms"]""")) }
        })
        assertTrue(runCatching { service.relayHeaders(origin, emptyMap()) }.exceptionOrNull() is IOException)
    }

    @Test
    fun listingHandlesRegionalResultsAndRejectsPaginationCycles() = runBlocking {
        var count = 0
        val service = service(get = { _, _ ->
            count++
            JSONObject().put("nextLink", JSONObject.NULL)
                .put("value", JSONArray().put(JSONObject().put("nextLink", JSONObject.NULL).put("value", JSONArray().put(tunnel()))))
        })
        assertEquals(1, service.discover(account.provider).size)
        assertEquals(1, count)
        val loop = service(get = { url, _ -> JSONObject().put("value", JSONArray()).put("nextLink", url) })
        assertTrue(runCatching { loop.discover(account.provider) }.exceptionOrNull() is IOException)
    }

    @Test
    fun loginUnavailableWithoutMicrosoftRegistrationAndPollingHonorsSlowDown() = runBlocking {
        val service = service()
        assertFalse(service.microsoftEnabled)
        assertTrue(runCatching { service.beginLogin(LoginProvider.Microsoft) }.exceptionOrNull() is IOException)
        assertEquals(5, oauthPollingInterval("authorization_pending", 5))
        assertEquals(10, oauthPollingInterval("slow_down", 5))
        assertTrue(runCatching { oauthPollingInterval("access_denied", 5) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { oauthPollingInterval("expired_token", 5) }.exceptionOrNull() is IOException)
    }

    @Test
    fun disabledMicrosoftCannotUseSavedCredentialsButCanSignOut() = runBlocking {
        val microsoft = LoginAccount(LoginProvider.Microsoft, "tenant:user", "Microsoft")
        val store = MemoryStore(AccountTokens(microsoft, "still-valid", "refresh", Long.MAX_VALUE))
        val service = service(
            store = store,
            paired = machine.copy(tunnelBinding = binding.copy(provider = microsoft.provider, accountId = microsoft.id)),
            get = { _, _ -> error("Disabled Microsoft must not make network requests") },
        )
        assertFalse(service.microsoftEnabled)
        assertTrue(runCatching { service.discover(microsoft.provider) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { service.relayHeaders(origin, emptyMap()) }.exceptionOrNull() is IOException)
        service.signOut(microsoft.provider)
        assertNull(store.tokens)
    }

    @Test
    fun credentialObjectsNeverIncludeTokensInToString() {
        assertFalse(AccountTokens(account, "SECRET", "REFRESH", null).toString().contains("SECRET"))
        assertFalse(DeviceLogin(account.provider, "SECRET", "CODE", "https://github.com/login/device", 1, 5).toString().contains("SECRET"))
    }

    @Test
    fun deviceLoginSavesIdentityOnlyAfterManagementAuthorizationSucceeds() = runBlocking {
        val store = MemoryStore(AccountTokens(account, "old", null, null))
        val service = service(store, get = { url, headers ->
            if (url == "https://api.github.com/user") {
                assertEquals("Bearer signed-in", headers["Authorization"])
                JSONObject("""{"id":42,"login":"owner"}""")
            } else {
                assertEquals("github signed-in", headers["Authorization"])
                JSONObject().put("value", JSONArray())
            }
        }, form = { url, body ->
            assertEquals("https://github.com/login/oauth/access_token", url)
            assertEquals("device-test", body["device_code"])
            assertFalse(body.containsKey("client_secret"))
            JSONObject("""{"access_token":"signed-in","expires_in":28800}""")
        })
        val login = DeviceLogin(LoginProvider.GitHub, "device-test", "USERCODE", "https://github.com/login/device",
            System.currentTimeMillis() + 60_000, 0)
        assertEquals(account, service.finishLogin(login))
        assertEquals("signed-in", store.tokens?.accessToken)

        val deniedService = service(store, get = { url, _ ->
            if (url == "https://api.github.com/user") JSONObject("""{"id":43,"login":"other"}""")
            else throw TunnelLoginRequired()
        }, form = { _, _ -> JSONObject("""{"access_token":"unusable"}""") })
        assertTrue(runCatching { deniedService.finishLogin(login) }.exceptionOrNull() is TunnelLoginRequired)
        assertEquals("signed-in", store.tokens?.accessToken)
    }

    @Test
    fun rejectsUnexpectedOAuthVerificationHost() = runBlocking {
        val service = service(form = { _, _ -> JSONObject("""{
            "device_code":"private","user_code":"code","verification_uri":"https://evil.example/login/device",
            "expires_in":900,"interval":5
        }""") })
        assertTrue(runCatching { service.beginLogin(LoginProvider.GitHub) }.isFailure)
    }

    @Test
    fun acceptsMicrosoftDeviceLoginVerificationHost() = runBlocking {
        val service = TunnelAccounts(
            MemoryStore(AccountTokens(account, "identity-secret", null, null)),
            { emptyList() },
            { _, _ -> error("Unexpected account request") },
            { url, body ->
                assertEquals("https://login.microsoftonline.com/common/oauth2/v2.0/devicecode", url)
                assertEquals("own-client-id", body["client_id"])
                JSONObject("""{
                    "device_code":"private","user_code":"code","verification_uri":"https://login.microsoft.com/device",
                    "expires_in":900,"interval":5
                }""")
            },
            { _, _, _ -> error("Unexpected pairing") },
            microsoftClientId = "own-client-id",
        )

        val login = service.beginLogin(LoginProvider.Microsoft)

        assertEquals("https://login.microsoft.com/device", login.verificationUri)
    }

    @Test
    fun emptyRegionsAreNotFailuresButRegionErrorsAreNeverHidden() = runBlocking {
        val empty = service(get = { _, _ -> JSONObject("""{"value":[{"regionName":"region","value":null,"error":null}]}""") })
        assertTrue(empty.discover(account.provider).isEmpty())
        val failed = service(get = { _, _ -> JSONObject("""{"value":[{"regionName":"region","error":{"code":"Unavailable"}}]}""") })
        assertTrue(runCatching { failed.discover(account.provider) }.exceptionOrNull() is IOException)
    }
}

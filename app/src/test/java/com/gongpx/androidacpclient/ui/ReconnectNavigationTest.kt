package com.gongpx.androidacpclient.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gongpx.androidacpclient.data.bridge.BridgeClient
import com.gongpx.androidacpclient.data.bridge.ChatConnection
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.tunnel.AccountPersistence
import com.gongpx.androidacpclient.data.tunnel.AccountTokens
import com.gongpx.androidacpclient.data.tunnel.LoginAccount
import com.gongpx.androidacpclient.data.tunnel.LoginProvider
import com.gongpx.androidacpclient.data.tunnel.TunnelAccounts
import com.gongpx.androidacpclient.data.tunnel.TunnelBinding
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ReconnectNavigationTest {
    @get:Rule val compose = createComposeRule()

    private class MemoryStore(@Volatile var tokens: AccountTokens?) : AccountPersistence {
        @Volatile var unreadable = false

        override fun load(provider: LoginProvider): AccountTokens? {
            if (unreadable) throw SecurityException("Unreadable test credentials")
            return tokens?.takeIf { it.account.provider == provider }
        }

        override fun save(tokens: AccountTokens) { this.tokens = tokens }
        override fun remove(provider: LoginProvider) { tokens = null }
    }

    @Test fun machinesNavigationDoesNotWaitForReconnectRelayGrant() = blockedReconnect(oauth = false)

    @Test fun machinesNavigationDoesNotWaitForMicrosoftTokenRefresh() = blockedReconnect(oauth = true)

    private fun blockedReconnect(oauth: Boolean) {
        val provider = if (oauth) LoginProvider.Microsoft else LoginProvider.GitHub
        val account = LoginAccount(provider, "test-owner", "Owner")
        val store = MemoryStore(AccountTokens(account, "test-access", "test-refresh", if (oauth) 1 else null))
        val origin = "https://test-tunnel-4317.usw2.devtunnels.ms"
        val machine = Machine("machine", "Computer", origin, "test-device", "test-fingerprint",
            tunnelBinding = TunnelBinding(provider, account.id, "usw2", "test-tunnel", 4317))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        fun blockRequest() {
            entered.countDown()
            try {
                check(release.await(15, TimeUnit.SECONDS)) { "Navigation waited for the blocked network request" }
            } finally {
                finished.countDown()
            }
        }
        val accounts = TunnelAccounts(store, { listOf(machine) }, { _, _ ->
            if (!oauth) blockRequest()
            JSONObject("""{
                "clusterId":"usw2","tunnelId":"test-tunnel","labels":["agentlink"],
                "accessTokens":{"connect":"test-connect"},
                "ports":[{"portNumber":4317,"protocol":"http","labels":["agentlink"],
                    "portForwardingUris":["$origin"]}]
            }""")
        }, { _, _ ->
            check(oauth)
            blockRequest()
            JSONObject("""{"access_token":"test-fresh","refresh_token":"test-rotated","expires_in":3600}""")
        }, { _, _, _ -> error("Unexpected pairing") }, microsoftClientId = "test-client")

        MockWebServer().use { server ->
            server.start()
            val bridge = BridgeClient(accountHeaders = { _, headers -> accounts.relayHeaders(origin, headers) })
            var connection: ChatConnection? = null
            compose.setContent {
                MaterialTheme {
                    var machinesVisible by remember { mutableStateOf(false) }
                    Column {
                        Button(onClick = { machinesVisible = false }) { Text("Chats") }
                        Button(onClick = { machinesVisible = true }) { Text("Machines") }
                        if (machinesVisible) {
                            AccountDiscoveryCard(false, accounts, {}, {})
                        } else {
                            Button(onClick = {
                                connection?.close()
                                connection = bridge.openChatConnection(
                                    machine.copy(endpoint = server.url("/").toString()),
                                    "chat", "copilot", "C:\\repo", 0,
                                )
                            }) { Text("Reconnect") }
                        }
                    }
                }
            }
            try {
                compose.onNodeWithText("Reconnect").performClick()
                assertTrue("Reconnect did not reach account refresh", entered.await(5, TimeUnit.SECONDS))
                repeat(2) {
                    compose.onNodeWithText("Machines").performClick()
                    compose.onNodeWithText("Loading saved accounts…").assertExists()
                    compose.onNodeWithText("Sign in with ${provider.name}").assertDoesNotExist()
                    compose.onNodeWithText("Chats").performClick()
                    compose.onNodeWithText("Reconnect").performClick()
                    assertEquals("Navigation must finish before the network request", 1L, finished.count)
                }
                compose.onNodeWithText("Machines").performClick()
                assertEquals(1L, finished.count)
                release.countDown()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText("Find · Owner").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Find · Owner").assertExists()
                if (oauth) assertEquals("test-rotated", store.tokens?.refreshToken)
            } finally {
                compose.runOnIdle { connection?.close() }
                release.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun unreadableAccountCanBeRetriedWithoutPretendingItIsSignedOut() {
        val account = LoginAccount(LoginProvider.GitHub, "test-owner", "Owner")
        val store = MemoryStore(AccountTokens(account, "test-access", null, null)).apply { unreadable = true }
        val accounts = TunnelAccounts(store, { emptyList() },
            { _, _ -> error("Unexpected network access") },
            { _, _ -> error("Unexpected OAuth") },
            { _, _, _ -> error("Unexpected pairing") })
        val changed = AtomicBoolean(false)
        compose.setContent { MaterialTheme { AccountDiscoveryCard(false, accounts, {}, { changed.set(true) }) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Could not access saved account credentials securely.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Sign in with GitHub").assertDoesNotExist()
        assertFalse(changed.get())
        store.unreadable = false
        compose.onNodeWithText("Retry loading accounts").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Find · Owner").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Could not access saved account credentials securely.").assertDoesNotExist()
        assertFalse(changed.get())
    }
}

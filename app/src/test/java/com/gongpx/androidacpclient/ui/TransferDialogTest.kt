package com.gongpx.androidacpclient.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.store.AppLanguageMode
import com.gongpx.androidacpclient.data.pairing.TransferBundle
import com.gongpx.androidacpclient.data.pairing.TransferLink
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransferDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun incomingLinkNeverImportsUntilPasswordPreviewAndExplicitConfirmation() {
        val password = "separately shared password"
        val machine = Machine("m", "Computer", "wss://example.devtunnels.ms", "device-secret", "sha256:" + "a".repeat(64))
        val link = TransferLink.encrypt(TransferBundle(listOf(machine), emptyList()), password)
        var imports = 0
        var closed = false
        compose.setContent {
            MaterialTheme {
                TransferDialog(emptyList(), emptyList(), link, { _, headers -> headers }, {
                    imports++
                    assertEquals("device-secret", it.machines.single().deviceToken)
                }, { closed = true })
            }
        }
        assertEquals(0, imports)
        compose.onNodeWithText("Original transfer password").performTextInput(password)
        compose.onNodeWithText("Preview import").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Confirm import").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, imports)
        compose.onNodeWithText("Computer\nwss://example.devtunnels.ms").assertExists()
        compose.onNodeWithText("Confirm import").performClick()
        compose.waitUntil(3_000) { closed }
        assertEquals(1, imports)
    }

    @Test fun currentIncomingLinkPreviewsWithoutPasswordButStillNeedsConfirmation() {
        val machine = Machine("m", "Computer", "wss://example.devtunnels.ms", "device-secret", "sha256:" + "a".repeat(64))
        val link = TransferLink.encrypt(TransferBundle(listOf(machine), emptyList()))
        var imports = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppStrings provides AppStrings.Chinese) {
                    TransferDialog(emptyList(), emptyList(), link, { _, headers -> headers }, { imports++ }, {})
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("确认导入").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("数据迁移").assertExists()
        compose.onNodeWithText("原迁移口令").assertDoesNotExist()
        assertEquals(0, imports)
        compose.onNodeWithText("确认导入").performClick()
        compose.waitUntil(3_000) { imports == 1 }
    }

    @Test fun selectingOneChatIncludesOnlyItsMachineAndExportsDirectlyToClipboard() {
        val machine = Machine("m", "Computer", "wss://example.devtunnels.ms", "device-secret", "sha256:" + "a".repeat(64))
        val other = machine.copy(id = "other", displayName = "Other computer", endpoint = "wss://other.devtunnels.ms")
        val chat = Chat("c", "Selected chat", "m", "Computer", "w", "Project", "C:\\repo", "agent", "Agent", 1)
        lateinit var context: Context
        compose.setContent {
            context = LocalContext.current
            MaterialTheme {
                TransferDialog(listOf(machine, other), listOf(chat, chat.copy(id = "ignored", title = "Unselected chat")),
                    null, { _, _ -> mapOf("X-Tunnel-Authorization" to "tunnel fresh-auth") }, {}, {})
            }
        }
        compose.onNodeWithText("None").performClick()
        compose.onNodeWithText("Export and copy link").assertIsNotEnabled()
        compose.onNodeWithText("Selected chat").performClick()
        compose.onNodeWithText("1 machines · 1 chats selected").assertExists()
        compose.onNodeWithText("Export and copy link").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Copy link again").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
            val link = clip.getItemAt(0).text.toString()
            assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
            val bundle = TransferLink.decrypt(link)
            assertEquals(listOf("m"), bundle.machines.map { it.id })
            assertEquals("tunnel fresh-auth", bundle.machines.single().connectionHeaders["X-Tunnel-Authorization"])
            assertEquals(listOf("c"), bundle.chats.map { it.id })
        }
    }

    @Test fun settingsMigrationEntryAndLabelsFollowSelectedLanguage() {
        val language = mutableStateOf(AppStrings.English)
        var opened = false
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppStrings provides language.value) {
                    SettingsScreen(
                        padding = PaddingValues(), updateState = UpdateUiState(),
                        languageMode = AppLanguageMode.English, onLanguageModeChange = {},
                        sessionLoadMessageLimit = 100, onSessionLoadMessageLimitChange = {},
                        onCheckForUpdate = {}, onOpenUpdate = {}, onOpenFeedbackIssue = {}, onEmailDeveloper = {},
                        notificationsEnabled = true, onNotificationSettings = {},
                        transferEnabled = true, onDataTransfer = { opened = true },
                    )
                }
            }
        }
        compose.onNodeWithText("Settings").assertExists()
        compose.onNodeWithText("Data migration").performClick()
        assertTrue(opened)
        compose.runOnIdle { language.value = AppStrings.Chinese }
        compose.onNodeWithText("设置").assertExists()
        compose.onNodeWithText("数据迁移").assertExists()
        compose.onNodeWithText("Data migration").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("会话历史加载"))
        compose.onNodeWithText("会话历史加载").assertExists()
        compose.onNodeWithText("Session load 历史").assertDoesNotExist()
    }
}

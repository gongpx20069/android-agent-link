package com.gongpx.androidacpclient.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gongpx.androidacpclient.data.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class InteractionReviewTest {
    @get:Rule val compose = createComposeRule()
    private val agents = listOf(Agent("kimi-cli", "Kimi", "available"), Agent("qwen-code", "Qwen", "available"))
    private fun machine(id: String) = Machine(id, "Computer $id", "http://localhost", "token", "fingerprint", agents = agents)
    private fun chat(id: String) = Chat(id, "Chat $id", "A", "Computer A", "workspace", "Project", "C:\\repo",
        "kimi-cli", "Kimi", 1, agentStatus = "idle")

    @Composable
    private fun Screen(
        machines: List<Machine> = listOf(machine("A")),
        chats: List<Chat> = emptyList(),
        selected: String? = null,
        operations: Set<String> = emptySet(),
        busy: Set<String> = emptySet(),
        onCancel: (Chat) -> Unit = {},
        onCreate: (Machine, String, Agent) -> Unit = { _, _, _ -> },
        onLoad: (Machine, Agent, (Result<List<AgentSessionInfo>>) -> Unit) -> Unit = { _, _, _ -> },
        onSend: (Chat, String) -> Boolean = { _, _ -> true },
    ) {
        MaterialTheme {
            ChatsScreen(PaddingValues(), machines, chats, busy, emptySet(), selected, emptyList(),
                chats.map { it.id }.toSet(), emptySet(), operations,
                { _, _ -> }, {}, {}, onCreate, { _, _, _ -> }, onLoad, {}, {}, {}, {}, { _, _ -> },
                { _, _ -> }, onSend, onCancelTask = onCancel)
        }
    }

    @Test fun backgroundRefreshDoesNotChangeComputerAgentOrWorkspaceSelection() {
        val machines = mutableStateOf(listOf(machine("A"), machine("B")))
        var created: Triple<String, String, String>? = null
        compose.setContent { Screen(machines.value, onCreate = { m, path, agent -> created = Triple(m.id, path, agent.id) }) }
        compose.onNodeWithText("Computer B").performScrollTo().performClick()
        compose.onNodeWithText("Qwen").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("C:\\chosen")
        compose.runOnIdle { machines.value = machines.value.map { it.copy(bridgeVersion = "updated") } }
        compose.onNodeWithText("Create Chat").performScrollTo().performClick()
        assertEquals(Triple("B", "C:\\chosen", "qwen-code"), created)
    }

    @Test fun changingAgentDiscardsLateSessionListFromPreviousAgent() {
        var completion: ((Result<List<AgentSessionInfo>>) -> Unit)? = null
        compose.setContent { Screen(onLoad = { _, _, result -> completion = result }) }
        compose.onNodeWithText("Existing session").performScrollTo().performClick()
        compose.onNodeWithText("Load sessions").performScrollTo().performClick()
        assertNotNull(completion)
        compose.onNodeWithText("Qwen").performScrollTo().performClick()
        compose.runOnIdle { completion!!(Result.success(listOf(AgentSessionInfo("old", "Wrong agent session", null, null)))) }
        compose.onNodeWithText("Wrong agent session").assertDoesNotExist()
        compose.onNodeWithText("Load sessions").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Open Session").assertDoesNotExist()
    }

    @Test fun allSessionsRemainReachableAndRecoveryRequiresConfirmation() {
        val sessions = (1..25).map { AgentSessionInfo("session-$it", "Session $it", null, null, false) }
        var chosen: String? = null
        compose.setContent { MaterialTheme {
            ResumeDialog(ResumeDialogState(chat("A"), sessions, null), {}, { chosen = it.sessionId }, {})
        } }
        compose.onNodeWithText("Resume").assertIsNotEnabled()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(24)
        compose.onNodeWithText("Session 25").performClick()
        assertNull(chosen)
        compose.onNodeWithText("This agent cannot replay history.", substring = true).assertExists()
        compose.onNodeWithText("Resume").performClick()
        assertEquals("session-25", chosen)
    }

    @Test fun selectingConfigurationDoesNotApplyItBeforeConfirmation() {
        val option = ConfigOption("mode", "Mode", "mode", "select", "normal", listOf(
            ConfigOptionValue("normal", "Normal", null), ConfigOptionValue("auto", "Automatic", null)))
        var applied: String? = null
        compose.setContent { MaterialTheme { ModelDialog(ModelDialogState(chat("A"), option), {}, { applied = it.value }) } }
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.onNodeWithText("Automatic").performClick()
        assertNull(applied)
        compose.onNodeWithText("Review permission changes", substring = true).assertExists()
        compose.onNodeWithText("Apply").performClick()
        assertEquals("auto", applied)
    }

    @Test fun draftsSurviveChatNavigationAndRejectedSubmission() {
        val selected = mutableStateOf<String?>("A")
        val accepted = mutableStateOf(false)
        compose.setContent { Screen(chats = listOf(chat("A"), chat("B")), selected = selected.value,
            onSend = { _, _ -> accepted.value }) }
        compose.onNode(hasSetTextAction()).performTextInput("draft A")
        compose.runOnIdle { selected.value = null }
        compose.runOnIdle { selected.value = "B" }
        compose.onNode(hasSetTextAction()).performTextInput("draft B")
        compose.runOnIdle { selected.value = "A" }
        compose.onNode(hasSetTextAction()).assertTextEquals("draft A")
        compose.onNodeWithText("Send").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("draft A")
        compose.runOnIdle { accepted.value = true }
        compose.onNodeWithText("Send").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("")
    }

    @Test fun sessionChangeBlocksSendingAndKeepsDraft() {
        compose.setContent { Screen(chats = listOf(chat("A")), selected = "A", operations = setOf("A")) }
        compose.onNode(hasSetTextAction()).performTextInput("keep this")
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("Updating session…").assertExists()
        compose.onNode(hasSetTextAction()).assertTextEquals("keep this")
    }

    @Test fun stopTargetsCurrentChatWithoutSubmittingDraft() {
        var stopped: String? = null
        var sends = 0
        compose.setContent { Screen(chats = listOf(chat("A")), selected = "A", busy = setOf("A"),
            onCancel = { stopped = it.id }, onSend = { _, _ -> sends++; true }) }
        compose.onNode(hasSetTextAction()).performTextInput("not sent")
        compose.onNodeWithText("Stop current task").performClick()
        assertEquals("A", stopped)
        assertEquals(0, sends)
        compose.onNode(hasSetTextAction()).assertTextEquals("not sent")
    }

    @Test fun firstRunOffersQrFirstWithoutHidingAccountDiscovery() {
        compose.setContent { MaterialTheme {
            MachinesScreen(PaddingValues(), emptyList(), null, { Text("Account discovery") }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Scan QR").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        compose.onNodeWithText("Account discovery").assertIsDisplayed()
    }
}

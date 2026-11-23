package com.gongpx.androidacpclient.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
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
        onDelete: (Chat) -> Unit = {},
        deleting: Set<String> = emptySet(),
        onOpen: (Chat) -> Unit = {},
        onReorder: (List<String>) -> Unit = {},
    ) {
        MaterialTheme {
            ChatsScreen(PaddingValues(), machines, chats, busy, emptySet(), selected, emptyList(),
                chats.map { it.id }.toSet(), emptySet(), operations,
                { _, _ -> }, {}, {}, onCreate, { _, _, _ -> }, onLoad, onOpen, onDelete, {}, {}, { _, _ -> },
                { _, _ -> }, onSend, onCancelTask = onCancel, deletingChatIds = deleting, onReorderChats = onReorder)
        }
    }

    @Test fun longPressDragReordersOnReleaseWithoutOpeningOrDeleting() {
        val chats = mutableStateOf(listOf(chat("A"), chat("B"), chat("C")))
        var saved: List<String>? = null
        var opened = 0
        compose.setContent {
            Screen(chats = chats.value, onOpen = { opened++ }, onDelete = { fail("Drag must not delete") },
                onReorder = { ids ->
                    saved = ids
                    chats.value = ids.map { id -> chats.value.first { it.id == id } }
                })
        }
        compose.onNodeWithText("Chat B").performScrollTo()
        val first = compose.onNodeWithText("Chat A").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("Chat B").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput {
            down(first.center)
            advanceEventTime(700)
            moveTo(second.center + Offset(0f, 20f), 250)
        }
        compose.mainClock.advanceTimeBy(32)
        assertNull(saved)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(listOf("B", "A", "C"), saved)
        assertEquals(0, opened)
        compose.onNodeWithText("Delete shared chat?").assertDoesNotExist()
        compose.onNodeWithText("Chat B").performClick()
        assertEquals(1, opened)
        val upper = compose.onNodeWithText("Chat B").fetchSemanticsNode().boundsInRoot
        val lower = compose.onNodeWithText("Chat A").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput {
            down(lower.center)
            advanceEventTime(700)
            moveTo(upper.center - Offset(0f, 20f), 250)
        }
        compose.mainClock.advanceTimeBy(32)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(listOf("A", "B", "C"), saved)
        assertEquals(1, opened)
    }

    @Test fun cancelledDragDoesNotSaveAndLiveUpdatesAreNotOverwritten() {
        val chats = mutableStateOf(listOf(chat("A"), chat("B"), chat("C")))
        var writes = 0
        compose.setContent { Screen(chats = chats.value, onReorder = { writes++ }) }
        compose.onNodeWithText("Chat B").performScrollTo()
        val first = compose.onNodeWithText("Chat A").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("Chat B").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput {
            down(first.center)
            advanceEventTime(700)
            moveTo(second.center + Offset(0f, 20f), 250)
        }
        compose.mainClock.advanceTimeBy(32)
        assertTrue(compose.onNodeWithText("Chat B").fetchSemanticsNode().boundsInRoot.top < second.top)
        compose.runOnIdle { chats.value = chats.value.map { if (it.id == "A") it.copy(title = "Updated A") else it } }
        compose.onRoot().performTouchInput { cancel() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0, writes)
        compose.onNodeWithText("Updated A").assertExists()
        val a = compose.onNodeWithText("Updated A").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithText("Chat B").fetchSemanticsNode().boundsInRoot
        assertTrue(a.top < b.top)
    }

    @Test fun dragAtEdgeScrollsPastInitiallyVisibleChatsAndCommitsOnce() {
        val chats = mutableStateOf((1..20).map { chat("$it") })
        val original = chats.value.map { it.id }
        var saved: List<String>? = null
        var writes = 0
        compose.setContent { Screen(chats = chats.value, onReorder = { saved = it; writes++ }) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        val first = compose.onNodeWithText("Chat 1").fetchSemanticsNode().boundsInRoot
        val list = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput {
            down(first.center)
            advanceEventTime(700)
            moveTo(Offset(first.center.x, list.bottom - 12f), 250)
        }
        compose.mainClock.advanceTimeBy(1800)
        assertNull(saved)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(1, writes)
        assertTrue("Should reach chats beyond the initial viewport: $saved", saved!!.indexOf("1") >= 5)
        assertEquals(original.drop(1), saved!!.filterNot { it == "1" })
    }

    @Test fun removingDraggedChatCancelsAndAccessibilityCanMoveUp() {
        val chats = mutableStateOf(listOf(chat("A"), chat("B"), chat("C")))
        var saved: List<String>? = null
        compose.setContent { Screen(chats = chats.value, onReorder = { saved = it }) }
        compose.onNodeWithText("Chat B").performScrollTo()
        val first = compose.onNodeWithText("Chat A").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("Chat B").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput {
            down(first.center)
            advanceEventTime(700)
            moveTo(second.center + Offset(0f, 20f), 250)
        }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { chats.value = chats.value.filterNot { it.id == "A" } }
        compose.mainClock.advanceTimeBy(32)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertNull(saved)
        val actions = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and hasAnyDescendant(hasText("Chat C"))
        ).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertTrue(actions.first { it.label == "Move up" }.action())
        }
        assertEquals(listOf("C", "B"), saved)
    }

    @Test fun topEdgeScrollWorksWithDifferentCardHeights() {
        val chats = (1..20).map {
            chat("$it").copy(connectionError = if (it % 2 == 0) "Offline\nReconnect to the computer\nCached status" else null)
        }
        var saved: List<String>? = null
        compose.setContent { Screen(chats = chats, onReorder = { saved = it }) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(10)
        val row = compose.onNodeWithText("Chat 9").fetchSemanticsNode().boundsInRoot
        val list = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput {
            down(row.center)
            advanceEventTime(700)
            moveTo(Offset(row.center.x, list.top + 12f), 250)
        }
        compose.mainClock.advanceTimeBy(1800)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertNotNull(saved)
        assertTrue("Should move above the original viewport: $saved", saved!!.indexOf("9") <= 3)
        assertEquals(chats.map { it.id }.filterNot { it == "9" }, saved!!.filterNot { it == "9" })
    }

    @Test fun ordinaryVerticalSwipeScrollsWithoutReordering() {
        var writes = 0
        compose.setContent { Screen(chats = (1..15).map { chat("$it") }, onReorder = { writes++ }) }
        val list = compose.onNode(hasScrollToIndexAction())
        list.performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(0, writes)
        compose.onNodeWithText("New Chat").assertIsNotDisplayed()
    }

    @Test fun sharedDeletionRequiresConfirmationAndKeepsCardWhilePending() {
        val deleting = mutableStateOf(emptySet<String>())
        var requests = 0
        compose.setContent {
            Screen(chats = listOf(chat("A")), deleting = deleting.value,
                onDelete = { requests++; deleting.value = setOf(it.id) })
        }
        compose.onNodeWithText("Chat A").performScrollTo().performTouchInput { swipeLeft() }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete shared chat?").assertIsDisplayed()
        compose.onNodeWithText("Remove from this phone now", substring = true).assertExists()
        assertEquals(0, requests)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, requests)
        compose.onNodeWithText("Chat A").assertExists()
        compose.onNodeWithText("Delete").performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, requests)
        compose.onNodeWithText("Deleting from Server...").assertExists()
        compose.onNodeWithText("Chat A").assertIsNotEnabled()
        compose.runOnIdle { deleting.value = emptySet() }
        compose.onNodeWithText("Chat A").assertIsEnabled()
    }

    @Test fun renameActionIsAvailableAndSharedTitleRefreshKeepsDraft() {
        val current = mutableStateOf(chat("A"))
        var command = ""
        compose.setContent {
            Screen(chats = listOf(current.value), selected = "A", onSend = { _, text -> command = text; true })
        }
        compose.onNode(hasSetTextAction()).performTextInput("Keep my draft")
        compose.onNodeWithContentDescription("Chat actions").performClick()
        compose.onNodeWithText("rename").performClick()
        assertEquals("/rename", command)
        compose.runOnIdle { current.value = current.value.copy(title = "Server renamed") }
        compose.onNodeWithText("Server renamed").assertExists()
        compose.onNode(hasSetTextAction()).assertTextEquals("Keep my draft")
    }

    @Test fun missingAgentsRemainVisibleInNewSessionAfterCatalogRefresh() {
        val installed = listOf(
            Agent("claude-code", "Claude Code", "available"),
            Agent("copilot-cli", "GitHub Copilot CLI", "available"),
        )
        val missing = listOf(
            Agent("kimi-cli", "Kimi Code", "missing"),
            Agent("qwen-code", "Qwen Code", "missing"),
            Agent("deepseek-harness", "DeepSeek Harness", "missing"),
        )
        val machines = mutableStateOf(listOf(machine("A").copy(agents = installed)))
        compose.setContent { Screen(machines.value) }
        compose.runOnIdle { machines.value = listOf(machine("A").copy(agents = installed + missing)) }
        missing.forEach { agent ->
            compose.onNodeWithText(agent.displayName).performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("Create Chat").performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithText("GitHub Copilot CLI").performScrollTo().performClick()
        compose.onNodeWithText("Create Chat").performScrollTo().assertIsEnabled()
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
        compose.onNodeWithContentDescription("Send").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("draft A")
        compose.runOnIdle { accepted.value = true }
        compose.onNodeWithContentDescription("Send").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("")
    }

    @Test fun sessionChangeBlocksSendingAndKeepsDraft() {
        compose.setContent { Screen(chats = listOf(chat("A")), selected = "A", operations = setOf("A")) }
        compose.onNode(hasSetTextAction()).performTextInput("keep this")
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNodeWithText("Updating session…").assertExists()
        compose.onNode(hasSetTextAction()).assertTextEquals("keep this")
    }

    @Test fun stopTargetsCurrentChatWithoutSubmittingDraft() {
        var stopped: String? = null
        var sends = 0
        compose.setContent { Screen(chats = listOf(chat("A")), selected = "A", busy = setOf("A"),
            onCancel = { stopped = it.id }, onSend = { _, _ -> sends++; true }) }
        compose.onNode(hasSetTextAction()).performTextInput("not sent")
        compose.onNodeWithContentDescription("Stop current task").performClick()
        assertEquals("A", stopped)
        assertEquals(0, sends)
        compose.onNode(hasSetTextAction()).assertTextEquals("not sent")
    }

    @Test fun accountDiscoveryComesBeforeQrForEmptyAndSavedMachines() {
        val machines = mutableStateOf<List<Machine>>(emptyList())
        compose.setContent { MaterialTheme {
            MachinesScreen(PaddingValues(), machines.value, null, { Text("Account discovery") }, {}, {}, {}, {})
        } }
        fun assertAccountFirst() {
            val account = compose.onNodeWithText("Account discovery").assertIsDisplayed().fetchSemanticsNode()
            val qr = compose.onNodeWithText("Scan QR").assertIsDisplayed().fetchSemanticsNode()
            assertTrue("Account login must appear above QR pairing", account.boundsInRoot.top < qr.boundsInRoot.top)
        }
        assertAccountFirst()
        compose.runOnIdle { machines.value = (1..10).map { machine(it.toString()) } }
        assertAccountFirst()
    }
}

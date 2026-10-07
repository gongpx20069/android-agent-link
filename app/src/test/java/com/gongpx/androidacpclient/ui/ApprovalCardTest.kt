package com.gongpx.androidacpclient.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.gongpx.androidacpclient.data.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ApprovalCardTest {
    @get:Rule val compose = createComposeRule()
    private val approval = Approval(
        "id", "chat", "Fix login", "machine", "Computer", "C:\\repo", "execute", "Run verification",
        createdAtMillis = 1, details = "gradle test", agentName = "Copilot",
        options = listOf(
            ApprovalOption("persistent", "Always allow", "allow_always"),
            ApprovalOption("once", "Allow once", "allow_once"),
            ApprovalOption("deny", "Deny this request", "reject_once"),
        ),
    )

    @Test fun persistentPermissionRequiresAnExplicitSecondConfirmationAndKeepsExactOption() {
        var answer: ApprovalAnswer? = null
        compose.setContent { MaterialTheme {
            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                ApprovalCard(approval, { _, value -> answer = value })
            }
        } }
        compose.onNodeWithText("Always allow").assertDoesNotExist()
        compose.onNodeWithText("Persistent choices").performScrollTo().performClick()
        compose.onNodeWithText("Always allow").performScrollTo().performClick()
        assertNull(answer)
        compose.onNodeWithText("Confirm persistent choice").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertNull(answer)
        compose.onNodeWithText("Always allow").performClick()
        compose.onNodeWithText("Confirm choice").performClick()
        assertEquals("persistent", answer?.optionId)
        assertEquals(ApprovalStatus.Approved, answer?.status)
    }

    @Test fun denialUsesExactRejectOptionAndSubmittingDisablesAllDecisions() {
        val item = mutableStateOf(approval)
        var answer: ApprovalAnswer? = null
        compose.setContent { MaterialTheme {
            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                ApprovalCard(item.value, { _, value -> answer = value })
            }
        } }
        compose.onNodeWithText("Deny this request").performScrollTo().performClick()
        assertEquals(ApprovalAnswer(ApprovalStatus.Denied, "deny"), answer)
        compose.runOnIdle { item.value = approval.copy(status = ApprovalStatus.Submitting) }
        compose.onNodeWithText("Allow once").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Persistent choices").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Deny this request").performScrollTo().assertIsNotEnabled()
    }

    @Test fun expiredRequestHasNoEnabledDecisionAndResolvedCardHasNoActionButtons() {
        val item = mutableStateOf(approval.copy(expiresAtMillis = 1))
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { ApprovalCard(item.value, { _, _ -> fail() }) }
        } }
        compose.onNodeWithText("Allow once").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Deadline passed").assertExists()
        compose.runOnIdle { item.value = approval.copy(status = ApprovalStatus.Approved) }
        compose.onNodeWithText("Approved").assertExists()
        compose.onNodeWithText("Allow once").assertDoesNotExist()
        compose.onNodeWithText("Deny this request").assertDoesNotExist()
    }

    @Test fun truncatedPreviewStillOpensCompletePagedDetails() {
        val details = "first-line\n" + "x".repeat(5000) + "\nlast-line"
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ApprovalCard(approval.copy(details = details), { _, _ -> })
            }
        } }
        compose.onNodeWithText("Full details").performScrollTo().performClick()
        compose.onNodeWithText("Exact operation details").assertIsDisplayed()
        compose.onNodeWithText("Next").performScrollTo().performClick()
        compose.onNodeWithText("last-line", substring = true).assertExists()
        compose.onNodeWithText("Copy details").assertExists()
    }

    @Test fun approvalCenterPrioritizesPendingAndNeverUsesSwipeToDeny() {
        var removed = false
        var decided = false
        compose.setContent { MaterialTheme {
            ApprovalsScreen(PaddingValues(), listOf(approval.copy(id = "old", summary = "Old request", status = ApprovalStatus.Denied), approval),
                { _, _ -> decided = true }, { removed = true })
        } }
        compose.onNodeWithText("Old request").assertDoesNotExist()
        compose.onNodeWithText("Run verification").performScrollTo().performTouchInput { swipeLeft() }
        compose.onNodeWithText("Delete").assertDoesNotExist()
        assertFalse(removed)
        assertFalse(decided)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("History (1)"))
        compose.onNodeWithText("History (1)").performClick()
        compose.onNodeWithText("Old request").performScrollTo().assertIsDisplayed()
    }

    @Test fun newestArrivalAppearsAtTopEvenWhenReadingOlderRequests() {
        val requests = mutableStateOf(listOf(
            approval.copy(summary = "Old request"),
            approval.copy(id = "new", summary = "New request", createdAtMillis = 2),
        ))
        compose.setContent { MaterialTheme {
            ApprovalsScreen(PaddingValues(), requests.value, { _, _ -> }, {})
        } }
        compose.onNodeWithText("New request").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Old request"))
        compose.onNodeWithText("Old request").assertIsDisplayed()
        compose.runOnIdle {
            requests.value = requests.value + approval.copy(id = "latest", summary = "Latest request", createdAtMillis = 3)
        }
        compose.onNodeWithText("Latest request").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        compose.onNodeWithText("Latest request").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.onNodeWithText("New request").assertIsDisplayed()
    }

    @Test fun summaryAndMetadataStayCompactButDetailsDialogShowsCompleteContent() {
        val summary = "Summary ".repeat(40) + "request-end"
        val workspace = "C:\\" + "long-directory\\".repeat(12) + "target"
        compose.setContent { MaterialTheme {
            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                ApprovalCard(approval.copy(summary = summary, workspacePath = workspace), { _, _ -> })
            }
        } }
        compose.onNodeWithText(summary).assertDoesNotExist()
        compose.onNodeWithText(approvalPreview(summary)).assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(approvalPreview(summary)).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        assertTrue(layouts.single().lineCount <= 2)
        compose.onNodeWithText("Full details").performScrollTo().performClick()
        compose.onNodeWithText(summary).assertExists()
        compose.onNode(hasText(workspace, substring = true) and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("gradle test") and hasAnyAncestor(isDialog())).assertExists()
    }

    @Test fun notificationFocusRevealsExactApprovalAfterLongTranscript() {
        val chat = Chat("chat", "Title", "m", "Computer", "ws", "Project", "C:\\repo", "agent", "Agent", 1,
            messages = (1..40).map { ChatMessage(MessageRole.Agent, "Message $it", it.toLong()) })
        var revealed = false
        compose.setContent { MaterialTheme {
            ChatDetailScreen(PaddingValues(), chat, true, ConnectionState.Online,
                listOf(approval, approval.copy(id = "second", summary = "Second request")), { _, _ -> },
                false, false, {}, {}, {}, { false }, {}, {}, approvalToReveal = "second",
                onApprovalRevealed = { revealed = true })
        } }
        compose.onNodeWithText("Second request").assertIsDisplayed()
        compose.onNodeWithText("2 awaiting your response").assertIsDisplayed()
        assertTrue(revealed)
    }

    @Test fun newApprovalDoesNotStealHistoryScrollButReviewJumpsToCard() {
        val chat = Chat("chat", "Title", "m", "Computer", "ws", "Project", "C:\\repo", "agent", "Agent", 1,
            messages = (1..40).map { ChatMessage(MessageRole.Agent, "Message $it", it.toLong()) })
        val pending = mutableStateOf(emptyList<Approval>())
        compose.setContent { MaterialTheme {
            ChatDetailScreen(PaddingValues(), chat, true, ConnectionState.Online, pending.value, { _, _ -> },
                false, false, {}, {}, {}, { false }, {}, {})
        } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        compose.onNodeWithText("Message 1").assertIsDisplayed()
        compose.runOnIdle { pending.value = listOf(approval, approval.copy(
            id = "new", summary = "Newest request", createdAtMillis = 2,
        )) }
        compose.onNodeWithText("Message 1").assertIsDisplayed()
        compose.onNodeWithText("2 awaiting your response").assertIsDisplayed()
        compose.onNodeWithText("Review").performClick()
        compose.onNodeWithText("Newest request").assertIsDisplayed()
    }

    @Test fun staleNotificationDoesNotKeepChatInApprovalFocusMode() {
        var consumed = false
        val chat = Chat("chat", "Title", "m", "Computer", "ws", "Project", "C:\\repo", "agent", "Agent", 1)
        compose.setContent { MaterialTheme {
            ChatDetailScreen(PaddingValues(), chat, false, ConnectionState.Online, emptyList(), { _, _ -> },
                false, false, {}, {}, {}, { false }, {}, {}, approvalToReveal = "resolved",
                onApprovalRevealed = { consumed = true })
        } }
        compose.runOnIdle { assertTrue(consumed) }
    }

    @Test fun unavailableSingleUseChoiceNeverFallsBackToGenericApproval() {
        var answer: ApprovalAnswer? = null
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ApprovalCard(approval.copy(options = listOf(approval.options.first())), { _, value -> answer = value })
            }
        } }
        compose.onNodeWithText("Allow once").assertDoesNotExist()
        compose.onNodeWithText("Approve").assertDoesNotExist()
        compose.onNodeWithText("No one-time allow option was supplied.").assertExists()
        compose.onNodeWithText("Deny").performScrollTo().performClick()
        assertEquals(ApprovalAnswer(ApprovalStatus.Denied), answer)
    }
}

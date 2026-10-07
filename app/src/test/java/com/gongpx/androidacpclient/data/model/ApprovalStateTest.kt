package com.gongpx.androidacpclient.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalStateTest {
    private val chat = Chat("chat", "Title", "machine", "Machine", "ws", "Workspace", "C:\\repo", "agent", "Agent", 1)
    private val request = BridgeApprovalRequest("approval", "execute", "Run tests", "{\"command\":\"gradle test\"}", 10, 300)

    @Test
    fun newestRequestsComeFirstRegardlessOfStatusAndNewArrivalsWinTimestampTies() {
        val first = request.toApproval(chat, 20)
        val newer = first.copy(id = "newer", createdAtMillis = 30, status = ApprovalStatus.Submitting)
        val latestArrival = newer.copy(id = "latest", status = ApprovalStatus.Approved, decidedAtMillis = 99)
        assertEquals(listOf("latest", "newer", "approval"),
            listOf(first, newer, latestArrival).newestApprovalsFirst().map { it.id })
        assertEquals(listOf("newer", "approval"),
            listOf(newer, first.copy(status = ApprovalStatus.Approved, decidedAtMillis = 100)).newestApprovalsFirst().map { it.id })
    }

    @Test
    fun snapshotRestoresPendingApprovalAfterLocalStateLoss() {
        val restored = reconcileApprovalSnapshot(emptyList(), chat, listOf(request), 20).single()
        assertEquals(ApprovalStatus.Pending, restored.status)
        assertEquals(request.details, restored.details)
        assertEquals(10L, restored.createdAtMillis)
        assertEquals(300L, restored.expiresAtMillis)
        assertEquals("Agent", restored.agentName)
    }

    @Test
    fun decisionsRequirePendingStateAndAnUnexpiredDeadline() {
        val pending = request.toApproval(chat, 20)
        assertTrue(pending.canDecide(299))
        assertFalse(pending.canDecide(300))
        assertFalse(pending.copy(status = ApprovalStatus.Submitting).canDecide(20))
        assertFalse(pending.copy(status = ApprovalStatus.Denied).canDecide(20))
        assertTrue(pending.copy(expiresAtMillis = null).canDecide(Long.MAX_VALUE))
    }
    @Test
    fun emptySnapshotReconcilesOnlyMatchingChatWithoutClaimingDenial() {
        val pending = request.toApproval(chat, 20)
        val other = pending.copy(id = "other", chatId = "other-chat")
        val result = reconcileApprovalSnapshot(listOf(pending, other), chat, emptyList(), 30)
        assertEquals(ApprovalStatus.Unavailable, result[0].status)
        assertEquals(ApprovalStatus.Pending, result[1].status)
    }

    @Test
    fun acknowledgedDecisionIsNotOverwrittenByEmptySnapshot() {
        val resolved = request.toApproval(chat, 20).resolve("denied", 30)
        assertEquals(resolved, reconcileApprovalSnapshot(listOf(resolved), chat, emptyList(), 40).single())
        assertFalse(resolved.status.isActionable())
    }

    @Test
    fun uncertainSubmissionCanBeRetriedOnlyWhenSnapshotStillPending() {
        val submitting = request.toApproval(chat, 20).copy(status = ApprovalStatus.Submitting)
        assertEquals(ApprovalStatus.Pending, reconcileApprovalSnapshot(listOf(submitting), chat, listOf(request), 40).single().status)
        assertEquals(ApprovalStatus.Expired, submitting.resolve("expired", 40).status)
    }
}

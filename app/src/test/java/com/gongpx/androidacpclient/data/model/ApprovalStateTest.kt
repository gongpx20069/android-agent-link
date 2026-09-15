package com.gongpx.androidacpclient.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ApprovalStateTest {
    private val chat = Chat("chat", "Title", "machine", "Machine", "ws", "Workspace", "C:\\repo", "agent", "Agent", 1)
    private val request = BridgeApprovalRequest("approval", "execute", "Run tests", "{\"command\":\"gradle test\"}", 10, 300)

    @Test
    fun snapshotRestoresPendingApprovalAfterLocalStateLoss() {
        val restored = reconcileApprovalSnapshot(emptyList(), chat, listOf(request), 20).single()
        assertEquals(ApprovalStatus.Pending, restored.status)
        assertEquals(request.details, restored.details)
        assertEquals(10L, restored.createdAtMillis)
        assertEquals(300L, restored.expiresAtMillis)
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

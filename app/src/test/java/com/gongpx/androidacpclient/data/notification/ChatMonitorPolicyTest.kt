package com.gongpx.androidacpclient.data.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMonitorPolicyTest {
    @Test
    fun retriesAreBoundedAndNeverOverflow() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (0..6).map(::monitorRetryDelayMillis))
        assertEquals(1000L, monitorRetryDelayMillis(-1))
        assertEquals(30000L, monitorRetryDelayMillis(Int.MAX_VALUE))
    }

    @Test
    fun completionRequiresNewFinalOperation() {
        assertTrue(shouldNotifyMonitoredCompletion("op-new", "op-old", "completed", 0))
        assertTrue(shouldNotifyMonitoredCompletion("op-new", null, "completed", 0))
        assertFalse(shouldNotifyMonitoredCompletion("op-new", "op-new", "completed", 0))
        assertFalse(shouldNotifyMonitoredCompletion("", null, "completed", 0))
        assertFalse(shouldNotifyMonitoredCompletion("op-new", null, "completed", 1))
        assertTrue(shouldNotifyMonitoredCompletion("op-new", null, "failed", 0))
        assertFalse(shouldNotifyMonitoredCompletion("op-new", "op-new", "failed", 0))
        assertFalse(shouldNotifyMonitoredCompletion("op-new", null, "cancelled", 0))
    }

    @Test
    fun idleRequiresAuthoritativeSyncAndNoOutstandingWork() {
        assertTrue(canFinishMonitoredChat(true, "idle", 0, 0, 0, false))
        assertTrue(canFinishMonitoredChat(true, "failed", 0, 0, 0, false))
        assertFalse(canFinishMonitoredChat(false, "idle", 0, 0, 0, false))
        listOf("busy", "waitingApproval", "unknown", "disconnected").forEach {
            assertFalse(canFinishMonitoredChat(true, it, 0, 0, 0, false))
        }
        assertFalse(canFinishMonitoredChat(true, "idle", 1, 0, 0, false))
        assertFalse(canFinishMonitoredChat(true, "idle", 0, 1, 0, false))
        assertFalse(canFinishMonitoredChat(true, "idle", 0, 0, 1, false))
        assertFalse(canFinishMonitoredChat(true, "idle", 0, 0, 0, true))
    }

    @Test
    fun replayBeforeLastNotifiedTurnDoesNotNotifyAgain() {
        val order = listOf("op-old", "op-notified", "op-unseen")
        assertFalse(shouldNotifyMonitoredCompletion("op-old", "op-notified", "completed", 0, order))
        assertTrue(shouldNotifyMonitoredCompletion("op-unseen", "op-notified", "completed", 0, order))
        assertTrue(shouldNotifyMonitoredCompletion("op-new", "op-notified", "completed", 0, order))
    }

    @Test
    fun queueReplayWaitsForSafeSnapshot() {
        assertTrue(shouldRestoreMonitoredQueue(true, false, false))
        assertFalse(shouldRestoreMonitoredQueue(false, false, false))
        assertFalse(shouldRestoreMonitoredQueue(true, true, false))
        assertFalse(shouldRestoreMonitoredQueue(true, false, true))
    }
}

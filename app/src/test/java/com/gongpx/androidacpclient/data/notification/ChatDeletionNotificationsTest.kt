package com.gongpx.androidacpclient.data.notification

import android.app.NotificationManager
import com.gongpx.androidacpclient.data.model.Approval
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatDeletionNotificationsTest {
    @Test fun restartCleanupRemovesDeletedChatAlertsAndOrphanApprovalsOnly() {
        val context = RuntimeEnvironment.getApplication()
        val manager = ChatNotificationManager(context)
        val system = context.getSystemService(NotificationManager::class.java)
        val removed = Approval("removed-approval", "removed", "Removed", "m", "M", "w", "command", "Approve", createdAtMillis = 1)
        val retained = removed.copy(id = "retained-approval", chatId = "retained")
        manager.showCompletion("removed", "Removed", "Done")
        manager.showMonitorError("removed", "Removed", "Disconnected")
        manager.showApproval(removed)
        manager.showCompletion("retained", "Retained", "Done")
        manager.showApproval(retained)
        assertEquals(5, system.activeNotifications.size)
        manager.reconcileRemovedChats(setOf("removed"), listOf(retained))
        assertEquals(2, system.activeNotifications.size)
        assertEquals(setOf(null, "approval:retained-approval"), system.activeNotifications.map { it.tag }.toSet())
        manager.reconcileRemovedChats(setOf("removed"), listOf(retained))
        assertEquals(2, system.activeNotifications.size)
    }
}

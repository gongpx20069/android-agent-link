package com.gongpx.androidacpclient.data.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.gongpx.androidacpclient.data.model.Approval
import com.gongpx.androidacpclient.data.model.ApprovalStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ApprovalNotificationTest {
    private lateinit var manager: NotificationManager
    private lateinit var notifications: ChatNotificationManager
    private lateinit var context: Context
    private fun approval(id: String = "one") = Approval(
        id, "chat", "Private chat", "machine", "Computer", "C:\\private", "execute",
        "Run tests", createdAtMillis = System.currentTimeMillis(), details = "secret command",
        expiresAtMillis = System.currentTimeMillis() + 60_000,
    )

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        notifications = ChatNotificationManager(context)
    }

    @Test fun eachRequestInTheSameChatGetsAnIndependentHighImportanceNotificationAndRoute() {
        val first = approval()
        val second = approval("two")
        notifications.syncApprovals(emptyList(), listOf(first))
        notifications.syncApprovals(listOf(first), listOf(first, second))
        val posted = manager.activeNotifications
        assertEquals(setOf("approval:one", "approval:two"), posted.map { it.tag }.toSet())
        posted.forEach {
            val notification = it.notification
            assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(notification.channelId).importance)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals(Notification.FLAG_ONLY_ALERT_ONCE, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
            assertTrue(notification.timeoutAfter in 1..60_000)
            assertTrue(notification.actions.isNullOrEmpty())
            val intent = shadowOf(notification.contentIntent).savedIntent
            assertEquals("chat", intent.getStringExtra(EXTRA_CHAT_ID))
            assertEquals(it.tag.removePrefix("approval:"), intent.getStringExtra(EXTRA_APPROVAL_ID))
            assertFalse(notification.publicVersion.extras.toString().contains("Private chat"))
            assertFalse(notification.publicVersion.extras.toString().contains("secret command"))
        }
        notifications.cancelApproval(first.id)
        assertEquals("approval:two", manager.activeNotifications.single().tag)
    }

    @Test fun reconnectSnapshotReplayAndOwnerHandoffDoNotRepostDismissedRequests() {
        val first = approval()
        notifications.syncApprovals(emptyList(), listOf(first))
        manager.cancel("approval:one", 0)
        val nextOwner = ChatNotificationManager(context)
        nextOwner.syncApprovals(listOf(first), listOf(first))
        nextOwner.syncApprovals(listOf(first.copy(status = ApprovalStatus.Submitting)), listOf(first))
        assertTrue(manager.activeNotifications.isEmpty())
        nextOwner.syncApprovals(listOf(first), listOf(first, approval("new")))
        assertEquals("approval:new", manager.activeNotifications.single().tag)
    }

    @Test fun snapshotResolutionAndExpiryRemoveOnlyMatchingAlerts() {
        val first = approval()
        val second = approval("two")
        notifications.syncApprovals(emptyList(), listOf(first, second))
        notifications.syncApprovals(listOf(first), listOf(first.copy(status = ApprovalStatus.Approved)))
        assertEquals("approval:two", manager.activeNotifications.single().tag)
        notifications.syncApprovals(listOf(second), listOf(second.copy(expiresAtMillis = System.currentTimeMillis() - 1)))
        assertTrue(manager.activeNotifications.isEmpty())
        notifications.syncApprovals(emptyList(), listOf(first.copy(expiresAtMillis = 1), second.copy(status = ApprovalStatus.Denied)))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun missingSnapshotRequestIsCancelledWithoutCancellingAnotherChat() {
        val first = approval()
        val second = approval("two").copy(chatId = "other")
        notifications.syncApprovals(emptyList(), listOf(first, second))
        notifications.syncApprovals(listOf(first), emptyList())
        assertEquals("approval:two", manager.activeNotifications.single().tag)
    }

    @Test fun questionsHaveTheirOwnLabelWithoutExposingAnApprovalAction() {
        notifications.showApproval(approval().copy(interaction = "question"))
        val notification = manager.activeNotifications.single().notification
        assertTrue(notification.extras.getString(Notification.EXTRA_TITLE)!!.startsWith("Answer needed"))
        assertTrue(notification.actions.isNullOrEmpty())
    }

    @Test fun disabledApprovalChannelIsReportedAndDoesNotPost() {
        manager.deleteNotificationChannel("approval_requests")
        manager.createNotificationChannel(android.app.NotificationChannel(
            "approval_requests", "Approval requests", NotificationManager.IMPORTANCE_NONE,
        ))
        assertFalse(notifications.notificationsEnabled())
        notifications.showApproval(approval())
        assertTrue(manager.activeNotifications.isEmpty())
    }
}

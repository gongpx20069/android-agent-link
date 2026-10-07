package com.gongpx.androidacpclient.data.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.gongpx.androidacpclient.MainActivity
import com.gongpx.androidacpclient.R
import com.gongpx.androidacpclient.data.model.Approval
import com.gongpx.androidacpclient.data.model.canDecide
import com.gongpx.androidacpclient.data.model.isActionable

const val EXTRA_CHAT_ID = "com.gongpx.androidacpclient.extra.CHAT_ID"
const val EXTRA_APPROVAL_ID = "com.gongpx.androidacpclient.extra.APPROVAL_ID"

class ChatNotificationManager(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    init {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.chat_completion_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.chat_completion_channel_description)
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(MONITOR_CHANNEL_ID, context.getString(R.string.monitor_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, context.getString(R.string.monitor_alert_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(APPROVAL_CHANNEL_ID, context.getString(R.string.approval_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.approval_channel_description)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    fun notificationsEnabled(): Boolean =
        !(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) && notificationManager.areNotificationsEnabled() &&
            listOf(CHANNEL_ID, ALERT_CHANNEL_ID, MONITOR_CHANNEL_ID, APPROVAL_CHANNEL_ID).all {
                notificationManager.getNotificationChannel(it)?.importance != NotificationManager.IMPORTANCE_NONE
            }

    fun showCompletion(chatId: String, chatTitle: String, preview: String) {
        if (!canPost(CHANNEL_ID)) return
        notificationManager.notify(
            chatId.hashCode(),
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_agentlink_notification)
                .setContentTitle(chatTitle)
                .setContentText(preview)
                .setStyle(Notification.BigTextStyle().bigText(preview))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(openChat(chatId))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun showApproval(approval: Approval) {
        val now = System.currentTimeMillis()
        if (!approval.canDecide(now) || !canPost(APPROVAL_CHANNEL_ID)) return
        val title = context.getString(
            if (approval.interaction == "question") R.string.approval_question_title else R.string.approval_notification_title,
            approval.chatTitle,
        )
        val preview = approval.summary.take(500)
        val notification = Notification.Builder(context, APPROVAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agentlink_notification)
            .setContentTitle(title)
            .setContentText(preview)
            .setSubText(approval.machineName)
            .setStyle(Notification.BigTextStyle().bigText(preview))
            .setCategory(Notification.CATEGORY_EVENT)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(context, APPROVAL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_agentlink_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.monitor_approval_required))
                .build())
            .setContentIntent(openChat(approval.chatId, approval.id))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
        approval.expiresAtMillis?.let { notification.setTimeoutAfter(it - now) }
        notificationManager.notify(
            "approval:${approval.id}", 0,
            notification.build(),
        )
    }

    // Both socket owners use the durable approval records, not connection-local replay flags.
    fun syncApprovals(previous: List<Approval>, current: List<Approval>) {
        val now = System.currentTimeMillis()
        val known = previous.map { it.id }.toSet()
        val active = current.filter {
            it.status.isActionable() && (it.expiresAtMillis == null || now < it.expiresAtMillis)
        }.map { it.id }.toSet()
        previous.filterNot { it.id in active }.forEach { cancelApproval(it.id) }
        current.filter { it.id !in known }.forEach(::showApproval)
    }

    fun cancelApproval(approvalId: String) {
        notificationManager.cancel("approval:$approvalId", 0)
    }

    fun showMonitorError(chatId: String, chatTitle: String, message: String) {
        if (!canPost(ALERT_CHANNEL_ID)) return
        notificationManager.notify("monitor:$chatId", 0, alert(chatId, chatTitle, message))
    }

    fun ongoingNotification(chatCount: Int): Notification =
        Notification.Builder(context, MONITOR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agentlink_notification)
            .setContentTitle(context.getString(R.string.monitor_title))
            .setContentText(context.getString(R.string.monitor_description, chatCount))
            .setContentIntent(openChat(null))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

    private fun alert(chatId: String, title: String, message: String): Notification =
        Notification.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agentlink_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openChat(chatId))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()

    private fun canPost(channel: String): Boolean =
        notificationManager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            notificationManager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun openChat(chatId: String?, approvalId: String? = null): PendingIntent {
        val openChatIntent = Intent(context, MainActivity::class.java)
            .setAction("$ACTION_OPEN_CHAT.$chatId${approvalId?.let { ".approval.$it" }.orEmpty()}")
            .putExtra(EXTRA_CHAT_ID, chatId)
            .putExtra(EXTRA_APPROVAL_ID, approvalId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            chatId?.hashCode() ?: 0,
            openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun cancel(chatId: String) {
        notificationManager.cancel(chatId.hashCode())
        notificationManager.cancel("monitor:$chatId", 0)
    }

    private companion object {
        const val CHANNEL_ID = "chat_completions"
        const val MONITOR_CHANNEL_ID = "chat_monitor"
        const val ALERT_CHANNEL_ID = "chat_monitor_alerts"
        const val APPROVAL_CHANNEL_ID = "approval_requests"
        const val ACTION_OPEN_CHAT = "com.gongpx.androidacpclient.action.OPEN_CHAT"
    }
}

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

const val EXTRA_CHAT_ID = "com.gongpx.androidacpclient.extra.CHAT_ID"

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
    }

    fun notificationsEnabled(): Boolean =
        !(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) && notificationManager.areNotificationsEnabled() &&
            listOf(CHANNEL_ID, ALERT_CHANNEL_ID, MONITOR_CHANNEL_ID).all {
                notificationManager.getNotificationChannel(it)?.importance != NotificationManager.IMPORTANCE_NONE
            }

    fun showCompletion(chatId: String, chatTitle: String, preview: String) {
        if (!canPost(CHANNEL_ID)) return
        notificationManager.notify(
            chatId.hashCode(),
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_agentlink)
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
        if (!canPost(ALERT_CHANNEL_ID)) return
        notificationManager.notify(
            "approval:${approval.id}", 0,
            alert(approval.chatId, approval.chatTitle, context.getString(R.string.monitor_approval_required)),
        )
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
            .setSmallIcon(R.drawable.ic_agentlink)
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
            .setSmallIcon(R.drawable.ic_agentlink)
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

    private fun openChat(chatId: String?): PendingIntent {
        val openChatIntent = Intent(context, MainActivity::class.java)
            .setAction("$ACTION_OPEN_CHAT.$chatId")
            .putExtra(EXTRA_CHAT_ID, chatId)
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
        const val ACTION_OPEN_CHAT = "com.gongpx.androidacpclient.action.OPEN_CHAT"
    }
}

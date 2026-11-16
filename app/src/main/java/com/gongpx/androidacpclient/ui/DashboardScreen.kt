package com.gongpx.androidacpclient.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gongpx.androidacpclient.data.bridge.DashboardSnapshot
import com.gongpx.androidacpclient.data.model.Chat
import java.text.DateFormat
import java.util.Date

internal fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
internal fun DashboardWindow(active: Boolean, fullscreen: Boolean) {
    val window = LocalContext.current.activity()?.window
    DisposableEffect(window, active, fullscreen) {
        val hadKeepAwake = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val oldBehavior = controller?.systemBarsBehavior
        if (active) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (fullscreen) {
                controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (active && !hadKeepAwake) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (active && fullscreen) {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                oldBehavior?.let { controller?.systemBarsBehavior = it }
            }
        }
    }
}

@Composable
internal fun DashboardScreen(
    padding: PaddingValues,
    snapshot: DashboardSnapshot,
    refreshing: Boolean,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onAcknowledge: (String, String) -> Unit,
    onOpen: (Chat) -> Unit,
) {
    val strings = LocalAppStrings.current
    BackHandler(fullscreen) { onFullscreen(false) }
    Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Dashboard", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { onFullscreen(!fullscreen) }) {
                Text(strings.reliability(if (fullscreen) "Exit fullscreen" else "Fullscreen", if (fullscreen) "退出全屏" else "全屏"))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(strings.reliability("Awake here · Refresh every 3 min", "此页保持亮屏 · 每 3 分钟刷新"),
                style = MaterialTheme.typography.labelMedium)
            TextButton(enabled = !refreshing, onClick = onRefresh) {
                Text(strings.reliability(if (refreshing) "Refreshing..." else "Refresh", if (refreshing) "刷新中…" else "刷新"))
            }
        }
        if (snapshot.refreshedAt != 0L) Text(
            strings.reliability("Last check: ", "上次检查：") +
                DateFormat.getTimeInstance().format(Date(snapshot.refreshedAt)),
            style = MaterialTheme.typography.labelSmall,
        )
        if (snapshot.failedMachines.isNotEmpty()) Text(
            strings.reliability("${snapshot.failedMachines.size} machine(s) unavailable. Cached states are marked stale.",
                "${snapshot.failedMachines.size} 台电脑无法连接，缓存状态已标注为未更新。"),
            color = MaterialTheme.colorScheme.error,
        )
        if (snapshot.chats.isEmpty()) Text(strings.reliability("No chats to display.", "暂无可显示的聊天。"))
        LazyVerticalGrid(columns = GridCells.Adaptive(180.dp), modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)) {
            items(snapshot.chats, key = { "${it.chat.machineId}:${it.chat.id}" }) { item ->
                val chat = item.chat
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (item.changed) Color(0xFFFFD740) else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (item.changed) Color(0xFF211B00) else MaterialTheme.colorScheme.onSurfaceVariant,
                )) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        Text(chat.machineName, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        Text(if (item.stale) strings.reliability("Offline / stale", "离线／状态未更新") else
                            when (chat.agentStatus) {
                                "busy" -> strings.reliability("Working", "运行中")
                                "waitingApproval" -> strings.reliability("Approval needed", "等待审批")
                                "idle" -> strings.reliability("Ready", "空闲")
                                "failed" -> strings.reliability("Failed", "失败")
                                "interrupted" -> strings.reliability("Interrupted", "已中断")
                                else -> strings.reliability("Unconfirmed", "待确认")
                            })
                        if (chat.backgroundWork) Text(strings.reliability("Background work", "后台工作中"))
                        if (chat.scheduledTasks > 0) Text(strings.reliability("Schedules: ${chat.scheduledTasks}", "定时任务：${chat.scheduledTasks}"))
                        if (item.changed) TextButton(enabled = !refreshing, onClick = { onAcknowledge(chat.machineId, chat.id) }) {
                            Text(strings.reliability("Updated · Dismiss highlight", "有更新 · 取消高亮"), color = Color(0xFF211B00))
                        }
                        TextButton(onClick = { onOpen(chat) }, enabled = !item.stale) {
                            Text(strings.reliability("Open chat", "打开聊天"),
                                color = if (item.changed) Color(0xFF211B00) else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

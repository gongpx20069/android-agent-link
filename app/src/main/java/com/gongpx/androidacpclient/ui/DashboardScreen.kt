package com.gongpx.androidacpclient.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gongpx.androidacpclient.data.bridge.DashboardSnapshot
import com.gongpx.androidacpclient.data.bridge.DashboardChat
import com.gongpx.androidacpclient.data.model.Chat
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

internal fun dashboardColumns(count: Int, width: Float, height: Float): Int =
    (1..count.coerceAtLeast(1)).maxBy { columns ->
        val rows = (count + columns - 1) / columns
        min(width / columns / 1.65f, height / rows.coerceAtLeast(1))
    }

private fun DashboardChat.status(strings: AppStrings): String =
    if (stale) strings.reliability("Offline / stale", "离线／状态未更新") else when (chat.agentStatus) {
        "busy" -> strings.reliability("Working", "运行中")
        "waitingApproval" -> strings.reliability("Approval needed", "等待审批")
        "idle" -> strings.reliability("Ready", "空闲")
        "failed" -> strings.reliability("Failed", "失败")
        "interrupted" -> strings.reliability("Interrupted", "已中断")
        else -> strings.reliability("Unconfirmed", "待确认")
    }

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
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    BackHandler(fullscreen) { onFullscreen(false) }
    Column(Modifier.fillMaxSize().padding(padding).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(strings.tabLabel(AppTab.Dashboard), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(strings.reliability("${snapshot.chats.size} chats · Awake · 3 min",
                    "${snapshot.chats.size} 个聊天 · 常亮 · 3 分钟刷新"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(enabled = !refreshing, onClick = onRefresh) {
                Text(strings.reliability(if (refreshing) "Refreshing..." else "Refresh", if (refreshing) "刷新中…" else "刷新"))
            }
            TextButton(onClick = { onFullscreen(!fullscreen) }) {
                Text(strings.reliability(if (fullscreen) "Exit fullscreen" else "Fullscreen", if (fullscreen) "退出全屏" else "全屏"))
            }
        }
        if (snapshot.failedMachines.isNotEmpty()) Text(
            strings.reliability("${snapshot.failedMachines.size} machine(s) offline",
                "${snapshot.failedMachines.size} 台电脑离线"),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
        )
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp).testTag("dashboard-grid")) {
            if (snapshot.chats.isEmpty()) {
                Text(strings.reliability("No chats to display.", "暂无可显示的聊天。"), Modifier.align(Alignment.Center))
            } else {
                val columns = dashboardColumns(snapshot.chats.size, maxWidth.value, maxHeight.value)
                val rows = snapshot.chats.chunked(columns)
                val gap = min(6f, min(maxWidth.value / columns, maxHeight.value / rows.size) / 12f).dp
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    rows.forEach { row ->
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEach { item ->
                                key(item.chat.machineId, item.chat.id) {
                                    DashboardTile(item, Modifier.weight(1f).fillMaxHeight()) {
                                        selected = item.chat.machineId to item.chat.id
                                    }
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        if (snapshot.refreshedAt != 0L) Text(
            strings.reliability("Last check: ", "上次检查：") +
                DateFormat.getTimeInstance(DateFormat.SHORT, if (strings == AppStrings.Chinese) Locale.CHINESE else Locale.ENGLISH)
                    .format(Date(snapshot.refreshedAt)),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    snapshot.chats.firstOrNull { selected == (it.chat.machineId to it.chat.id) }?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(item.chat.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.chat.machineName)
                    Text(item.status(strings))
                    if (item.chat.backgroundWork) Text(strings.reliability("Background work", "后台工作中"))
                    if (item.chat.scheduledTasks > 0) Text(strings.reliability(
                        "Schedules: ${item.chat.scheduledTasks}", "定时任务：${item.chat.scheduledTasks}"))
                    if (item.changed) TextButton(enabled = !refreshing, onClick = {
                        onAcknowledge(item.chat.machineId, item.chat.id)
                        selected = null
                    }) { Text(strings.reliability("Dismiss highlight", "取消高亮")) }
                }
            },
            confirmButton = {
                TextButton(enabled = !item.stale, onClick = { selected = null; onOpen(item.chat) }) {
                    Text(strings.reliability("Open chat", "打开聊天"))
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text(strings.close) } },
        )
    }
}

@Composable
private fun DashboardTile(item: DashboardChat, modifier: Modifier, onClick: () -> Unit) {
    val strings = LocalAppStrings.current
    val status = item.status(strings)
    val activity = when {
        item.stale -> status
        item.chat.backgroundWork -> "$status · " + strings.reliability("Background", "后台")
        item.chat.scheduledTasks > 0 -> "$status · " + strings.reliability(
            "Schedules ${item.chat.scheduledTasks}", "定时 ${item.chat.scheduledTasks}")
        else -> status
    }
    val accent = when {
        item.stale -> MaterialTheme.colorScheme.outline
        item.chat.agentStatus == "busy" -> Color(0xFF399AFF)
        item.chat.agentStatus == "waitingApproval" -> Color(0xFFF2A900)
        item.chat.agentStatus in setOf("failed", "interrupted") -> MaterialTheme.colorScheme.error
        item.chat.agentStatus == "idle" -> Color(0xFF26A77B)
        else -> MaterialTheme.colorScheme.outline
    }
    val foreground = if (item.changed) Color(0xFF211B00) else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(10.dp)
    val updated = strings.reliability("Updated", "有更新")
    BoxWithConstraints(modifier.clip(shape)
        .background(if (item.changed) Color(0xFFFFD740) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        .border(1.dp, if (item.changed) Color(0xFFFFC400) else accent.copy(alpha = 0.3f), shape)
        .clickable(role = Role.Button, onClick = onClick)
        .testTag("dashboard-chat-${item.chat.machineId}-${item.chat.id}")
        .semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(item.chat.title, item.chat.machineName, status,
                updated.takeIf { item.changed },
                strings.reliability("Background work", "后台工作中").takeIf { item.chat.backgroundWork },
                strings.reliability("Schedules: ${item.chat.scheduledTasks}", "定时任务：${item.chat.scheduledTasks}")
                    .takeIf { item.chat.scheduledTasks > 0 }).joinToString(", ")
        }) {
        val inset = min(12f, min(maxWidth.value, maxHeight.value) / 10).dp
        val fontScale = LocalDensity.current.fontScale
        val titleSize = (min(maxWidth.value / 9, maxHeight.value / 4).coerceIn(7f, 24f) / fontScale).sp
        val detailSize = (min(13f, titleSize.value * fontScale * 0.8f) / fontScale).sp
        val tileHeight = maxHeight
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.fillMaxSize().padding(inset), verticalArrangement = Arrangement.Center) {
            Text(item.chat.title, color = foreground, fontSize = titleSize, lineHeight = titleSize * 1.15f,
                fontWeight = FontWeight.SemiBold, maxLines = if (tileHeight >= 110.dp) 2 else 1, overflow = TextOverflow.Ellipsis)
            Text(if (item.changed) "$updated · $activity" else activity, color = foreground.copy(alpha = 0.85f),
                fontSize = detailSize, lineHeight = detailSize * 1.2f, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (tileHeight >= 88.dp) Text(item.chat.machineName, color = foreground.copy(alpha = 0.65f),
                fontSize = detailSize, lineHeight = detailSize * 1.2f, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

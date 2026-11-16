package com.gongpx.androidacpclient.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
internal fun DashboardWindow(active: Boolean, fullscreen: Boolean, landscape: Boolean) {
    val activity = LocalContext.current.activity()
    val window = activity?.window
    // Rotation can recreate the Activity while its requested orientation is still overridden.
    val originalOrientation = rememberSaveable {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    DisposableEffect(activity, active) {
        onDispose {
            if (active && activity != null && !activity.isChangingConfigurations && !activity.isFinishing) {
                activity.requestedOrientation = originalOrientation
            }
        }
    }
    LaunchedEffect(activity, active, landscape) {
        if (active) activity?.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }
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
    landscape: Boolean,
    onFullscreen: (Boolean) -> Unit,
    onLandscape: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onAcknowledge: (String, String) -> Unit,
    onOpen: (Chat) -> Unit,
) {
    val strings = LocalAppStrings.current
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    BackHandler(fullscreen) { onFullscreen(false) }
    Column(Modifier.fillMaxSize().background(Color(0xFFF6F8FC)).padding(padding)
        .padding(horizontal = 16.dp, vertical = 10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val heading: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.size(38.dp).background(Color(0xFFE6EDFA), RoundedCornerShape(12.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(2) { row ->
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                repeat(2) { column ->
                                    Box(Modifier.weight(1f).fillMaxHeight().background(
                                        Color(0xFF4268AE).copy(alpha = if (row == column) 1f else 0.45f),
                                        RoundedCornerShape(2.dp)))
                                }
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(strings.tabLabel(AppTab.Dashboard), color = Color(0xFF233047),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(strings.reliability("${snapshot.chats.size} chats", "${snapshot.chats.size} 个聊天"),
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFF67758A))
                    }
                }
            }
            if (maxWidth < 600.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    heading()
                    DashboardActions(refreshing, fullscreen, landscape, onRefresh, onFullscreen, onLandscape, Modifier.fillMaxWidth())
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { heading() }
                    DashboardActions(refreshing, fullscreen, landscape, onRefresh, onFullscreen, onLandscape, Modifier.width(336.dp))
                }
            }
        }
        if (snapshot.failedMachines.isNotEmpty()) Text(
            strings.reliability("${snapshot.failedMachines.size} machine(s) offline",
                "${snapshot.failedMachines.size} 台电脑离线"),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
        )
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(vertical = 10.dp).testTag("dashboard-grid")) {
            if (snapshot.chats.isEmpty()) {
                Text(strings.reliability("No chats to display.", "暂无可显示的聊天。"), Modifier.align(Alignment.Center))
            } else {
                val columns = dashboardColumns(snapshot.chats.size, maxWidth.value, maxHeight.value)
                val rows = snapshot.chats.chunked(columns)
                val gap = min(10f, min(maxWidth.value / columns, maxHeight.value / rows.size) / 12f).dp
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
        Text(
            strings.reliability("Awake · Every 3 min", "常亮 · 每 3 分钟刷新") +
                if (snapshot.refreshedAt != 0L) strings.reliability(" · Checked ", " · 检查于 ") +
                DateFormat.getTimeInstance(DateFormat.SHORT, if (strings == AppStrings.Chinese) Locale.CHINESE else Locale.ENGLISH)
                    .format(Date(snapshot.refreshedAt)) else "",
            style = MaterialTheme.typography.labelSmall, color = Color(0xFF67758A),
            modifier = Modifier.align(Alignment.CenterHorizontally),
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
private fun DashboardActions(
    refreshing: Boolean,
    fullscreen: Boolean,
    landscape: Boolean,
    onRefresh: () -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onLandscape: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val strings = LocalAppStrings.current
    Row(modifier.background(Color.White, RoundedCornerShape(14.dp))
        .border(1.dp, Color(0xFFE5EAF2), RoundedCornerShape(14.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = !refreshing, onClick = onRefresh, modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF4F607A)), contentPadding = PaddingValues(4.dp)) {
            Text(strings.reliability(if (refreshing) "Refreshing..." else "Refresh", if (refreshing) "刷新中…" else "刷新"),
                maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = { onLandscape(!landscape) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF4F607A),
                containerColor = if (landscape) Color(0xFFE6EDFA) else Color.Transparent), contentPadding = PaddingValues(4.dp)) {
            Text(strings.reliability(if (landscape) "Auto rotate" else "Landscape", if (landscape) "自动旋转" else "横屏"),
                maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
        FilledTonalButton(onClick = { onFullscreen(!fullscreen) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF2F518C), contentColor = Color.White),
            contentPadding = PaddingValues(4.dp)) {
            Text(strings.reliability(if (fullscreen) "Exit fullscreen" else "Fullscreen", if (fullscreen) "退出全屏" else "全屏"),
                maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DashboardTile(item: DashboardChat, modifier: Modifier, onClick: () -> Unit) {
    val strings = LocalAppStrings.current
    val status = item.status(strings)
    val activity = when {
        item.stale -> strings.reliability("Offline", "离线")
        item.chat.backgroundWork -> "$status · " + strings.reliability("Background", "后台")
        item.chat.scheduledTasks > 0 -> "$status · " + strings.reliability(
            "Schedules ${item.chat.scheduledTasks}", "定时 ${item.chat.scheduledTasks}")
        else -> status
    }
    val (background, accent) = when {
        item.stale -> Color(0xFFECEFF3) to Color(0xFF647287)
        item.chat.agentStatus == "busy" -> Color(0xFFE7F1FF) to Color(0xFF2864AC)
        item.chat.agentStatus == "waitingApproval" -> Color(0xFFFFF3D6) to Color(0xFF976412)
        item.chat.agentStatus == "idle" -> Color(0xFFE4F5EB) to Color(0xFF277052)
        item.chat.agentStatus == "failed" -> Color(0xFFFCE6E9) to Color(0xFFAE414E)
        item.chat.agentStatus == "interrupted" -> Color(0xFFFFECDF) to Color(0xFFA95E34)
        else -> Color(0xFFEEE9FA) to Color(0xFF72589B)
    }
    val foreground = Color(0xFF233047)
    val shape = RoundedCornerShape(18.dp)
    val highlight = Color(0xFFFFC400)
    val updated = strings.reliability("Updated", "有更新")
    BoxWithConstraints(modifier.clip(shape)
        .background(Brush.linearGradient(listOf(lerp(background, Color.White, 0.3f), background)))
        .border(if (item.changed) 2.dp else 1.dp, if (item.changed) highlight else Color.White.copy(alpha = 0.85f), shape)
        .clickable(role = Role.Button, onClick = onClick)
        .testTag("dashboard-chat-${item.chat.machineId}-${item.chat.id}")
        .semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(item.chat.title, item.chat.machineName, status,
                updated.takeIf { item.changed },
                strings.reliability("Background work", "后台工作中").takeIf { item.chat.backgroundWork },
                strings.reliability("Schedules: ${item.chat.scheduledTasks}", "定时任务：${item.chat.scheduledTasks}")
                    .takeIf { item.chat.scheduledTasks > 0 }).joinToString(", ")
        }) {
        val inset = min(18f, min(maxWidth.value, maxHeight.value) / 7).dp
        val fontScale = LocalDensity.current.fontScale
        val titleSize = (min(maxWidth.value / 8, maxHeight.value / 4).coerceIn(7f, 26f) / fontScale).sp
        val detailSize = (min(12f, titleSize.value * fontScale * 0.72f) / fontScale).sp
        val tileHeight = maxHeight
        val tileWidth = maxWidth
        val gap = min(8f, maxHeight.value / 18).dp
        val chipPadding = PaddingValues(horizontal = min(7f, maxWidth.value / 24).dp,
            vertical = min(3f, maxHeight.value / 50).dp)
        Column(Modifier.fillMaxSize().padding(inset), verticalArrangement = if (tileHeight >= 125.dp) {
            Arrangement.SpaceBetween
        } else Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
            if (tileHeight >= 125.dp) Text(item.chat.machineName, color = foreground.copy(alpha = 0.65f),
                fontSize = detailSize, lineHeight = detailSize * 1.2f, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.chat.title, color = foreground, fontSize = titleSize, lineHeight = titleSize * 1.15f,
                fontWeight = FontWeight.SemiBold, maxLines = if (tileHeight >= 125.dp) 2 else 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                Row(Modifier.weight(1f, fill = false).background(Color.White.copy(alpha = 0.65f), RoundedCornerShape(7.dp))
                    .padding(chipPadding), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(min(5f, tileHeight.value / 20).dp)) {
                    Box(Modifier.size(min(5f, tileHeight.value / 15).dp).background(accent, CircleShape))
                    Text(activity, color = accent, fontSize = detailSize, lineHeight = detailSize * 1.2f,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (item.changed) {
                    if (tileHeight >= 110.dp && tileWidth >= 180.dp) {
                        Text(updated, Modifier.background(highlight, RoundedCornerShape(7.dp)).padding(chipPadding),
                            color = foreground, fontSize = detailSize, lineHeight = detailSize * 1.2f,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                    } else {
                        Box(Modifier.size(min(7f, tileHeight.value / 10).dp).background(highlight, CircleShape))
                    }
                }
            }
        }
    }
}

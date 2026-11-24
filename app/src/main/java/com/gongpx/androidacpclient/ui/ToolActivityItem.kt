package com.gongpx.androidacpclient.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gongpx.androidacpclient.data.model.ChatMessage
import com.gongpx.androidacpclient.data.model.ToolDetailPage
import com.gongpx.androidacpclient.data.model.parseToolCallDetails
import com.gongpx.androidacpclient.data.store.ToolDetailCache
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException

@Composable
internal fun ToolActivityItem(item: ChatMessage, source: ImagePromptContext?, cacheOverride: ToolDetailCache? = null) {
    val strings = LocalAppStrings.current
    val summary = remember(item.details) { parseToolCallDetails(item.details) }
    val ref = summary?.optString("detailRef").orEmpty()
    val version = summary?.optInt("detailVersion") ?: 0
    val terminal = summary?.optString("status") in setOf("completed", "failed", "cancelled")
    var expanded by remember(ref) { mutableStateOf(false) }
    var page by remember(ref) { mutableStateOf<ToolDetailPage?>(null) }
    var refresh by remember(ref) { mutableIntStateOf(0) }
    var handledRefresh by remember(ref) { mutableIntStateOf(0) }
    var cursor by remember(ref) { mutableIntStateOf(0) }
    var error by remember(ref) { mutableStateOf<String?>(null) }
    var loading by remember(ref) { mutableStateOf(false) }
    val context = LocalContext.current
    val cache = cacheOverride ?: remember(context) { ToolDetailCache(context.applicationContext) }
    val identity = source?.let { "${it.machine.id}:${it.machine.bridgeFingerprint}:${it.chatId}:$ref" }.orEmpty()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, ref) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) expanded = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // No version key: partial tool updates must not cause polling while expanded.
    LaunchedEffect(expanded, ref, terminal, refresh, cursor, identity) {
        if (!expanded) { loading = false; return@LaunchedEffect }
        if (terminal && page?.version != version && cursor > 0) {
            cursor = 0
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            if (source == null) throw IOException("Computer connection unavailable.")
            val current = page
            val force = refresh != handledRefresh
            val requestedCursor = if (terminal && current?.version != version) 0 else cursor
            val result = if (!force && current != null && current.version >= version && current.offset == requestedCursor) current
            else if (requestedCursor > 0 && current != null) {
                source.client.toolDetails(source.machine, source.chatId, ref, current.snapshot, requestedCursor)
            } else withContext(Dispatchers.IO) {
                val cached = if (!force) cache.read(identity, version) else null
                cached ?: source.client.toolDetails(source.machine, source.chatId, ref).also { cache.write(identity, it) }
            }
            page = result
            handledRefresh = refresh
        } catch (failure: IOException) {
            error = failure.message
        } catch (failure: JSONException) {
            error = "Invalid tool detail data: ${failure.message}"
        } finally {
            loading = false
        }
    }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.70f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(item.title ?: strings.agentActivity, style = MaterialTheme.typography.labelLarge)
                    Text(item.text, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (expanded) strings.hide else strings.details, style = MaterialTheme.typography.labelMedium)
            }
            if (expanded) {
                if (!terminal) Text(strings.reliability(
                    "Running · snapshot only; refresh to see newer output.", "运行中 · 当前为快照，可手动刷新。"),
                    style = MaterialTheme.typography.bodySmall)
                if (loading) Text(strings.reliability("Loading details...", "正在加载详情…"),
                    style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                page?.let { loaded ->
                    SelectionContainer {
                        Text(loaded.text, modifier = Modifier.horizontalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row {
                    TextButton(enabled = !loading, onClick = { cursor = 0; refresh++ }) {
                        Text(strings.reliability("Refresh", "刷新"))
                    }
                    if (page?.next != null) TextButton(enabled = !loading, onClick = { cursor = page!!.next!! }) {
                        Text(strings.reliability("Next page", "下一页"))
                    }
                    if ((page?.offset ?: 0) > 0) TextButton(enabled = !loading, onClick = { cursor = 0; refresh++ }) {
                        Text(strings.reliability("First page", "第一页"))
                    }
                }
            }
        }
    }
}

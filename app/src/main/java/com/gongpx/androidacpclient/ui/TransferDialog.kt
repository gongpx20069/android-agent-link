package com.gongpx.androidacpclient.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.pairing.TransferBundle
import com.gongpx.androidacpclient.data.pairing.TransferLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TransferDialog(
    machines: List<Machine>,
    chats: List<Chat>,
    initialLink: String?,
    relayHeaders: (String, Map<String, String>) -> Map<String, String>,
    onImport: suspend (TransferBundle) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val strings = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(initialLink != null) }
    var link by remember { mutableStateOf(initialLink.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var selectedMachines by remember { mutableStateOf(machines.map { it.id }.toSet()) }
    var selectedChats by remember { mutableStateOf(chats.map { it.id }.toSet()) }
    var decoded by remember { mutableStateOf<TransferBundle?>(null) }
    var exported by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val legacy = TransferLink.requiresPassword(link.trim())
    val chosenMachines = machines.filter { it.id in selectedMachines }
    val chosenChats = chats.filter { it.id in selectedChats && it.machineId in selectedMachines }
    val failure = strings.reliability("Cannot read this link. Copy the complete link from Data migration on the other phone.",
        "无法读取链接，请从另一台手机的「数据迁移」复制完整链接。")

    fun copyLink(value: String) {
        val clip = ClipData.newPlainText("AgentLink", value)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    LaunchedEffect(initialLink) {
        if (initialLink != null && !TransferLink.requiresPassword(initialLink.trim())) {
            busy = true
            try {
                decoded = withContext(Dispatchers.Default) { TransferLink.decrypt(initialLink.trim()) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = failure
            } finally {
                busy = false
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn,
            dismissOnClickOutside = false, dismissOnBackPress = !busy)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.reliability("Data migration", "数据迁移"), Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall)
                    TextButton(enabled = !busy, onClick = onClose) { Text(strings.close) }
                }
                TabRow(selectedTabIndex = if (importing) 1 else 0) {
                    listOf(strings.reliability("Export", "导出"), strings.reliability("Import", "导入")).forEachIndexed { index, title ->
                        Tab(selected = importing == (index == 1), enabled = !busy, onClick = {
                            importing = index == 1
                            decoded = null; exported = null; message = null; password = ""
                        }, text = { Text(title) })
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (importing) {
                        if (decoded == null) item {
                            OutlinedTextField(value = link, onValueChange = {
                                link = it.take(TransferLink.MAX_LINK_CHARS + 1)
                                decoded = null; password = ""; message = null
                            }, enabled = !busy, label = { Text(strings.reliability("Paste transfer link", "粘贴迁移链接")) },
                                maxLines = 3, modifier = Modifier.fillMaxWidth())
                        }
                        if (legacy && decoded == null) item {
                            Text(strings.reliability(
                                "This is an older password-protected link. Enter the password you set when exporting, or export a new link with the updated app.",
                                "这是旧版口令链接。请输入导出时自己设置的密码，或用新版应用重新导出，无需口令。"),
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = password, onValueChange = {
                                password = it.take(256); decoded = null; message = null
                            }, enabled = !busy, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                label = { Text(strings.reliability("Original transfer password", "原迁移口令")) },
                                modifier = Modifier.fillMaxWidth())
                        }
                        decoded?.let { bundle ->
                            item {
                                Text(strings.reliability(
                                    "${bundle.machines.size} machines · ${bundle.chats.size} chats. Review before importing. Existing history is kept.",
                                    "${bundle.machines.size} 台电脑 · ${bundle.chats.size} 个聊天。请核对后导入，已有聊天记录会保留。"))
                                TextButton(enabled = !busy, onClick = { decoded = null; message = null }) {
                                    Text(strings.reliability("Change link", "更换链接"))
                                }
                            }
                            items(bundle.machines, key = { "preview-machine-${it.id}" }) {
                                Text("${it.displayName}\n${it.endpoint}", style = MaterialTheme.typography.bodyMedium)
                            }
                            items(bundle.chats, key = { "preview-chat-${it.id}" }) {
                                Text("${it.title} · ${it.machineName}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(strings.reliability("Select machines and chats", "选择电脑和聊天"), Modifier.weight(1f))
                                TextButton(enabled = !busy, onClick = {
                                    selectedMachines = machines.map { it.id }.toSet()
                                    selectedChats = chats.map { it.id }.toSet()
                                    exported = null; message = null
                                }) { Text(strings.reliability("All", "全选")) }
                                TextButton(enabled = !busy, onClick = {
                                    selectedMachines = emptySet(); selectedChats = emptySet()
                                    exported = null; message = null
                                }) { Text(strings.reliability("None", "清空")) }
                            }
                        }
                        if (machines.isEmpty()) item {
                            Text(strings.reliability("No saved machines. Pair a machine first.", "暂无已保存的电脑，请先配对电脑。"))
                        }
                        machines.forEach { machine ->
                            val children = chats.filter { it.machineId == machine.id }
                            item(key = "machine-${machine.id}") {
                                TransferChoice(machine.displayName, strings.reliability("Machine", "电脑"),
                                    machine.id in selectedMachines, !busy) { checked ->
                                    val ids = children.map { it.id }.toSet()
                                    selectedMachines = if (checked) selectedMachines + machine.id else selectedMachines - machine.id
                                    selectedChats = if (checked) selectedChats + ids else selectedChats - ids
                                    exported = null; message = null
                                }
                            }
                            items(children, key = { "chat-${it.id}" }) { chat ->
                                Box(Modifier.padding(start = 24.dp)) {
                                    TransferChoice(chat.title, strings.reliability("Chat", "聊天"),
                                        chat.id in selectedChats, !busy) { checked ->
                                        selectedChats = if (checked) selectedChats + chat.id else selectedChats - chat.id
                                        if (checked) selectedMachines = selectedMachines + machine.id
                                        exported = null; message = null
                                    }
                                }
                            }
                        }
                        item {
                            Text(strings.reliability("Selecting a chat also includes its machine authorization.",
                                "选择聊天会自动包含所属电脑及其授权。"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        Text(strings.reliability(
                            "No account logins or transcript backups are included; expired tunnel access needs re-pairing.",
                            "不含账号登录或聊天正文备份；隧道授权过期需重新配对。"),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
                if (!importing) Text(strings.reliability(
                    "${chosenMachines.size} machines · ${chosenChats.size} chats selected",
                    "已选 ${chosenMachines.size} 台电脑 · ${chosenChats.size} 个聊天"),
                    style = MaterialTheme.typography.labelMedium)
                Text(strings.reliability("Anyone with the link can control these machines. Share only with trusted devices.",
                    "拿到链接即可控制这些电脑，请只分享给可信设备。"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp))
                Button(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    enabled = !busy && (if (importing) link.isNotBlank() && (!legacy || password.length >= 12)
                        else chosenMachines.isNotEmpty()),
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            try {
                                if (importing) {
                                    val pending = decoded
                                    if (pending == null) {
                                        decoded = withContext(Dispatchers.Default) { TransferLink.decrypt(link.trim(), password) }
                                        focus.clearFocus()
                                    } else {
                                        onImport(pending)
                                        onClose()
                                    }
                                } else {
                                    val result = exported ?: withContext(Dispatchers.IO) {
                                        val portable = chosenMachines.map {
                                            it.copy(connectionHeaders = relayHeaders(it.endpoint, it.connectionHeaders), tunnelBinding = null)
                                        }
                                        TransferLink.encrypt(TransferBundle(portable, chosenChats))
                                    }
                                    copyLink(result)
                                    exported = result
                                    message = strings.reliability(
                                        "Link copied. On the other phone open Settings > Data migration > Import and paste it. Both phones need the updated app.",
                                        "链接已复制。在另一台手机打开「设置 → 数据迁移 → 导入」并粘贴。两台手机均需更新应用。")
                                }
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                // Parser/network errors may contain credentials. Never display the raw exception.
                                message = strings.reliability(
                                    if (!importing) "Export failed. Check machine authorization or select fewer items."
                                    else if (decoded != null) "Import incomplete. Check identity conflicts, deleted chats or storage. Existing data is kept; retry is safe."
                                    else if (legacy) "Cannot unlock this older link. Check its original password or export a new link."
                                    else failure,
                                    if (!importing) "导出失败，请检查电脑授权，或减少所选数量。"
                                    else if (decoded != null) "导入未完成，请检查身份冲突、已删除聊天或存储状态。已有数据保留，可重试。"
                                    else if (legacy) "无法解锁旧版链接，请检查原口令，或用新版应用重新导出。"
                                    else failure)
                            } finally {
                                busy = false
                            }
                        }
                    }) {
                    Text(strings.reliability(
                        if (busy) "Please wait..." else if (!importing) {
                            if (exported == null) "Export and copy link" else "Copy link again"
                        } else if (decoded == null) "Preview import" else "Confirm import",
                        if (busy) "请稍候…" else if (!importing) {
                            if (exported == null) "导出并复制链接" else "再次复制链接"
                        } else if (decoded == null) "预览导入" else "确认导入"))
                }
            }
        }
    }
}

@Composable
private fun TransferChoice(title: String, kind: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = null)
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

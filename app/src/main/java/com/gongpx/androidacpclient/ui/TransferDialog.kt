package com.gongpx.androidacpclient.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
    val strings = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(initialLink != null) }
    var link by remember { mutableStateOf(initialLink.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(machines.map { it.id }.toSet()) }
    var includeChats by remember { mutableStateOf(true) }
    var decoded by remember { mutableStateOf<TransferBundle?>(null) }
    var exported by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val warning = strings.reliability(
        "This transfers Bridge access, including device tokens and current tunnel authorization. Anyone with the link AND password can control these machines. Send the password separately. Account login tokens, chat text, images and queued prompts are not exported. Tunnel authorization can expire; pair again via QR or account discovery on the other phone.",
        "迁移包含设备令牌及当前隧道授权。拿到链接和口令的人可以控制这些电脑，请分开传递口令。不导出账号登录令牌、聊天正文、图片及待发送消息。隧道授权可能过期，届时需在另一台手机扫码，或登录账号发现电脑后重新配对。",
    )
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, dismissOnClickOutside = !busy),
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(strings.reliability("Transfer chats / machines", "迁移聊天／电脑")) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(warning, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(enabled = !busy, onClick = {
                        importing = false; decoded = null; exported = null; message = null
                    }) { Text(strings.reliability("Export", "导出")) }
                    TextButton(enabled = !busy, onClick = {
                        importing = true; decoded = null; exported = null; message = null
                    }) { Text(strings.reliability("Import", "导入")) }
                }
                if (importing) {
                    OutlinedTextField(value = link, onValueChange = {
                        link = it.take(TransferLink.MAX_LINK_CHARS + 1); decoded = null; message = null
                    }, enabled = !busy, label = { Text(strings.reliability("Paste transfer link", "粘贴迁移链接")) },
                        maxLines = 3, modifier = Modifier.fillMaxWidth())
                } else {
                    machines.forEach { machine ->
                        Row {
                            Checkbox(checked = machine.id in selected, enabled = !busy, onCheckedChange = {
                                selected = if (it) selected + machine.id else selected - machine.id
                                exported = null
                            })
                            Text(machine.displayName, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    Row {
                        Checkbox(checked = includeChats, enabled = !busy, onCheckedChange = { includeChats = it; exported = null })
                        Text(strings.reliability("Include chat references", "包含聊天引用"), Modifier.padding(top = 12.dp))
                    }
                }
                OutlinedTextField(value = password, onValueChange = {
                    password = it.take(256); decoded = null; exported = null; message = null
                }, enabled = !busy, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(strings.reliability("Transfer password (12+ characters)", "迁移口令（至少 12 个字符）")) },
                    modifier = Modifier.fillMaxWidth())
                decoded?.let { bundle ->
                    Text(strings.reliability(
                        "Ready to import ${bundle.machines.size} machines and ${bundle.chats.size} chat references. Matching machine credentials will be updated; existing chat history is kept.",
                        "将导入 ${bundle.machines.size} 台电脑及 ${bundle.chats.size} 个聊天引用。同身份电脑的授权会更新；已有聊天历史保留。",
                    ))
                    bundle.machines.forEach { Text("${it.displayName}\n${it.endpoint}", style = MaterialTheme.typography.bodySmall) }
                }
                exported?.let { encrypted ->
                    Button(enabled = !busy, onClick = {
                        val clip = ClipData.newPlainText("AgentLink encrypted transfer", encrypted)
                        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                        message = strings.reliability("Encrypted link copied. Send the password separately.", "已复制加密链接，请单独传递口令。")
                    }) { Text(strings.reliability("Copy encrypted link", "复制加密链接")) }
                }
                message?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && password.length >= 12 && (if (importing) link.isNotBlank() else selected.isNotEmpty()),
                onClick = {
                    busy = true
                    message = null
                    if (!importing) exported = null
                    scope.launch {
                        try {
                            if (importing) {
                                val pending = decoded
                                if (pending == null) {
                                    decoded = withContext(Dispatchers.Default) { TransferLink.decrypt(link.trim(), password) }
                                } else {
                                    onImport(pending)
                                    onClose()
                                }
                            } else {
                                val chosen = machines.filter { it.id in selected }
                                val selectedChats = if (includeChats) chats.filter { it.machineId in selected } else emptyList()
                                exported = withContext(Dispatchers.IO) {
                                    val portable = chosen.map {
                                        it.copy(connectionHeaders = relayHeaders(it.endpoint, it.connectionHeaders), tunnelBinding = null)
                                    }
                                    TransferLink.encrypt(TransferBundle(portable, selectedChats), password)
                                }
                            }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            // Never echo parser/network exceptions: they may contain the incoming link or credentials.
                            message = strings.reliability(
                                if (decoded != null) "Import not completed. Check machine identity conflicts, deleted chats, or storage. Existing data is kept; a partially saved import can be retried."
                                else "Transfer failed. Check the link/password, selected item count, and machine authorization.",
                                if (decoded != null) "导入未完成。请检查电脑身份冲突、已删除聊天或存储状态。已有数据保留；部分保存后可重试。"
                                else "迁移失败，请检查链接／口令、所选数量及电脑授权。",
                            )
                        } finally {
                            busy = false
                        }
                    }
                }) {
                Text(strings.reliability(
                    if (busy) "Please wait..." else if (!importing) "Create encrypted link" else if (decoded == null) "Unlock preview" else "Confirm import",
                    if (busy) "请稍候…" else if (!importing) "生成加密链接" else if (decoded == null) "解密预览" else "确认导入",
                ))
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(strings.close) } },
    )
}

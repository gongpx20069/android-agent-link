package com.gongpx.androidacpclient.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.tunnel.DeviceLogin
import com.gongpx.androidacpclient.data.tunnel.DiscoveredTunnel
import com.gongpx.androidacpclient.data.tunnel.LoginProvider
import com.gongpx.androidacpclient.data.tunnel.TunnelAccounts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AccountDiscoveryCard(
    chinese: Boolean,
    accounts: TunnelAccounts,
    onPaired: (Machine) -> Unit,
    onAccountChanged: () -> Unit,
) {
    fun text(en: String, zh: String) = if (chinese) zh else en
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(accounts.accounts()) }
    var discovered by remember { mutableStateOf<List<DiscoveredTunnel>?>(null) }
    var selectedProvider by remember { mutableStateOf<LoginProvider?>(null) }
    var login by remember { mutableStateOf<DeviceLogin?>(null) }
    var pairingCode by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun runOperation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = null
        job = scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: java.io.IOException) {
                status = text("Could not complete: ", "未能完成：") + error.message
            } catch (_: org.json.JSONException) {
                status = text("The service returned an invalid response.", "服务返回了无效响应。")
            } catch (_: IllegalArgumentException) {
                status = text("The service returned invalid account or tunnel metadata.", "服务返回了无效的账号或隧道信息。")
            } catch (_: IllegalStateException) {
                status = text("Could not save account state securely.", "无法安全保存账号状态。")
            } finally {
                login = null
                pairingCode = null
                busy = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { job?.cancel() }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text("Sign in & find computers", "登录并发现电脑"), style = MaterialTheme.typography.titleLarge)
            Text(text(
                "Use the same account as the computer's Dev Tunnel. First pairing still requires confirmation on the computer.",
                "使用与电脑 Dev Tunnel 相同的账号。首次配对仍需在电脑上确认。",
            ))
            for (provider in LoginProvider.entries) {
                val account = signedIn.firstOrNull { it.provider == provider }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (account == null) {
                        Button(enabled = !busy && (provider != LoginProvider.Microsoft || accounts.microsoftEnabled), onClick = {
                            runOperation {
                                discovered = null
                                selectedProvider = provider
                                login = accounts.beginLogin(provider)
                                accounts.finishLogin(requireNotNull(login))
                                signedIn = accounts.accounts()
                                onAccountChanged()
                                discovered = accounts.discover(provider)
                            }
                        }) { Text(text("Sign in with ${provider.name}", "使用 ${provider.name} 登录")) }
                    } else {
                        OutlinedButton(enabled = !busy, onClick = {
                            runOperation {
                                selectedProvider = provider
                                discovered = null
                                discovered = accounts.discover(provider)
                            }
                        }) { Text(text("Find · ${account.label}", "发现 · ${account.label}")) }
                        TextButton(enabled = !busy, onClick = {
                            runOperation {
                                withContext(Dispatchers.IO) { accounts.signOut(provider) }
                                signedIn = accounts.accounts()
                                discovered = null
                                onAccountChanged()
                                status = text("Signed out on this phone. Saved chats are retained.", "已在此手机退出登录，聊天记录保留。")
                            }
                        }) { Text(text("Sign out", "退出")) }
                    }
                }
            }
            if (!accounts.microsoftEnabled) Text(
                text("Microsoft login is not enabled in this build: the publisher must complete app registration. QR pairing is still available.",
                    "此构建尚未启用 Microsoft 登录：发布者需要先完成应用注册。仍可使用扫码配对。"),
                style = MaterialTheme.typography.bodySmall,
            )
            login?.let { pending ->
                Text(text(
                    if (pending.provider == LoginProvider.GitHub) "Authorize Microsoft's Visual Studio Tunnel Service in your browser, then return here."
                    else "Authorize AgentLink in your browser, then return here.",
                    if (pending.provider == LoginProvider.GitHub) "在浏览器中授权微软的 Visual Studio Tunnel Service，然后返回这里。"
                    else "在浏览器中授权 AgentLink，然后返回这里。",
                ))
                SelectionContainer { Text(pending.userCode, style = MaterialTheme.typography.headlineMedium) }
                Button(onClick = {
                    clipboard.setText(AnnotatedString(pending.userCode))
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.verificationUri)))
                    } catch (_: android.content.ActivityNotFoundException) {
                        status = text("Install a browser to finish sign-in.", "请安装浏览器后完成登录。")
                    }
                }) { Text(text("Copy code & open sign-in", "复制代码并打开登录")) }
            }
            pairingCode?.let { code ->
                Text(text("Check that your computer shows this code, then type it at the bridge console:", "核对电脑上显示的代码一致，然后在 bridge 控制台输入："))
                SelectionContainer { Text(code, style = MaterialTheme.typography.headlineMedium) }
            }
            if (busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    TextButton(onClick = {
                        job?.cancel()
                        status = text("Cancelled. If the computer still shows a pairing prompt, press Enter there before retrying.", "已取消。若电脑仍显示配对确认提示，请先在电脑按回车取消，再重试。")
                    }) { Text(text("Cancel", "取消")) }
                }
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            discovered?.let { computers ->
                if (computers.isEmpty()) Text(text(
                    "No AgentLink computers found for ${selectedProvider?.name}. Start the updated bridge with the same login provider and account, then refresh.",
                    "未发现 ${selectedProvider?.name} 账号下的 AgentLink 电脑。请用相同登录方式和账号启动更新后的 bridge，再点击发现。",
                ))
                for (computer in computers) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(computer.name, style = MaterialTheme.typography.titleMedium)
                        Text("${computer.binding.clusterId} · ${computer.binding.tunnelId}:${computer.binding.port}", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled = !busy, onClick = {
                            runOperation {
                                val machine = accounts.pair(computer) { pairingCode = it }
                                onPaired(machine)
                                status = text("Computer paired.", "电脑已配对。")
                            }
                        }) { Text(text("Pair this computer", "配对此电脑")) }
                    }
                }
            }
        }
    }
}

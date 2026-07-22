package com.gongpx.androidacpclient.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.gongpx.androidacpclient.BuildConfig
import com.gongpx.androidacpclient.data.bridge.BridgeClient
import com.gongpx.androidacpclient.data.bridge.ChatConnection
import com.gongpx.androidacpclient.data.model.Agent
import com.gongpx.androidacpclient.data.model.AgentPlanEntryPriority
import com.gongpx.androidacpclient.data.model.AgentPlanEntryStatus
import com.gongpx.androidacpclient.data.model.AgentSessionInfo
import com.gongpx.androidacpclient.data.model.Approval
import com.gongpx.androidacpclient.data.model.ApprovalStatus
import com.gongpx.androidacpclient.data.model.AvailableCommand
import com.gongpx.androidacpclient.data.model.BridgeApprovalRequest
import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.model.ChatMessage
import com.gongpx.androidacpclient.data.model.ChatMessageKind
import com.gongpx.androidacpclient.data.model.ConfigOption
import com.gongpx.androidacpclient.data.model.ConfigOptionValue
import com.gongpx.androidacpclient.data.model.ConnectionState
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.model.MarkdownTable
import com.gongpx.androidacpclient.data.model.MarkdownTableAlignment
import com.gongpx.androidacpclient.data.model.MessageRole
import com.gongpx.androidacpclient.data.model.QueuedPrompt
import com.gongpx.androidacpclient.data.model.acceptPrompt
import com.gongpx.androidacpclient.data.model.bindAcpSession
import com.gongpx.androidacpclient.data.model.bindBridgeEventGeneration
import com.gongpx.androidacpclient.data.model.finishPrompt
import com.gongpx.androidacpclient.data.model.isFinalPromptCompletion
import com.gongpx.androidacpclient.data.model.isTerminalPromptStatus
import com.gongpx.androidacpclient.data.model.markQueuedPromptRemoving
import com.gongpx.androidacpclient.data.model.markdownCodeFenceDelimiterLength
import com.gongpx.androidacpclient.data.model.parseMarkdownTable
import com.gongpx.androidacpclient.data.model.parseAgentPlan
import com.gongpx.androidacpclient.data.model.recordBridgeEventId
import com.gongpx.androidacpclient.data.model.reconcileRecentSessionMessages
import com.gongpx.androidacpclient.data.model.shouldClearBusyAfterCancellation
import com.gongpx.androidacpclient.data.model.shouldApplyChatStatus
import com.gongpx.androidacpclient.data.model.startQueuedPrompt
import com.gongpx.androidacpclient.data.notification.ChatNotificationManager
import com.gongpx.androidacpclient.data.notification.chatCompletionAttention
import com.gongpx.androidacpclient.data.notification.chatCompletionPreview
import com.gongpx.androidacpclient.data.pairing.PairingLinkParser
import com.gongpx.androidacpclient.data.store.AppLanguageMode
import com.gongpx.androidacpclient.data.store.AppSettingsStore
import com.gongpx.androidacpclient.data.store.ChatStore
import com.gongpx.androidacpclient.data.store.MachineStore
import com.gongpx.androidacpclient.data.update.AppUpdate
import com.gongpx.androidacpclient.data.update.UpdateClient
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private enum class AppTab(val icon: String) {
    Chats("✦"),
    Approvals("✓"),
    Machines("▣"),
    Settings("⚙"),
}

private enum class NewChatMode {
    NewSession,
    ExistingSession,
}

private val LocalAppStrings = staticCompositionLocalOf { AppStrings.English }
private const val FEEDBACK_ISSUES_URL = "https://github.com/gongpx20069/android-agent-link/issues/new"
private const val DEVELOPER_EMAIL = "gongpx20069@vip.qq.com"

private data class AppStrings(
    val mobileControlSubtitle: String,
    val settings: String,
    val updates: String,
    val language: String,
    val languageSystem: String,
    val languageEnglish: String,
    val languageChinese: String,
    val languageDescription: String,
    val sessionLoadHistory: String,
    val sessionLoadHistoryDescription: String,
    val sessionLoadHistoryLabel: String,
    val manageSettings: String,
    val currentVersion: (String) -> String,
    val checking: String,
    val checkForUpdates: String,
    val downloadVersion: (String) -> String,
    val updateInstallNote: String,
    val feedback: String,
    val feedbackDescription: String,
    val openGitHubIssue: String,
    val developerContact: String,
    val emailDeveloper: String,
    val noReleasesFound: String,
    val updateAvailable: (String) -> String,
    val latestVersion: String,
    val updateCheckFailed: (String?) -> String,
    val checkingForUpdates: String,
    val chats: String,
    val approvals: String,
    val machines: String,
    val newChat: String,
    val newSession: String,
    val existingSession: String,
    val chooseMachineWorkspaceAgent: String,
    val tapCreateAgentSession: String,
    val pairMachineFirst: String,
    val noAgentDiscovered: String,
    val remoteWorkspacePath: String,
    val remoteWorkspacePlaceholder: String,
    val createChat: String,
    val loadingSessions: String,
    val loadSessions: String,
    val couldNotLoadSessions: (String?) -> String,
    val noResumableSessions: String,
    val noResumableSessionsForAgent: String,
    val openSession: String,
    val conversations: (Int) -> String,
    val noChatsYet: String,
    val createChatAfterPairing: String,
    val messages: (Int) -> String,
    val back: String,
    val send: String,
    val appendPrompt: String,
    val queuedPrompts: (Int) -> String,
    val removeQueuedPrompt: String,
    val prompt: String,
    val current: String,
    val close: String,
    val on: String,
    val off: String,
    val configOptionsNotLoaded: String,
    val resumeSession: String,
    val loadingSessionsFrom: (String) -> String,
    val noResumableSessionsWorkspace: String,
    val agentActivity: String,
    val plan: String,
    val hide: String,
    val details: String,
    val approvalsSubtitle: String,
    val pending: (Int) -> String,
    val noApprovalRequests: String,
    val approvalRequestsAppear: String,
    val approve: String,
    val deny: String,
    val addMachine: String,
    val addMachineSubtitle: String,
    val scanQr: String,
    val pairLink: String,
    val pairingLinkFallback: String,
    val machinesPaired: (Int) -> String,
    val noMachinesPaired: String,
    val startBridgePairMachine: String,
    val testConnection: String,
    val agents: String,
    val delete: String,
    val selected: String,
    val scanPairingQr: String,
    val pointCameraAtBridgeQr: String,
    val cameraAccessNeeded: String,
    val grantCameraPermission: String,
    val invalidPairingLink: String,
    val waitingBridgeApproval: (String) -> String,
    val machineOnline: (String) -> String,
    val pairedHealthFailed: (String?) -> String,
    val pairingFailed: (String?) -> String,
    val machineUnavailable: String,
    val approvalRequired: (String) -> String,
    val approvalRequiredTitle: String,
    val bridgeWebSocketFailed: (String?) -> String,
    val chatCreatedSystem: String,
    val chatStatusBusy: String,
    val chatStatusReady: String,
    val chatStatusOffline: String,
    val loadingExistingAcpSession: (String) -> String,
    val openSessionFailed: (String?) -> String,
    val resumeFailed: (String?) -> String,
    val eventHistoryExpired: String,
    val setModel: String,
    val modelChangeFailed: (String?) -> String,
    val connectionFailed: (String?) -> String,
    val agentFinished: String,
) {
    fun tabLabel(tab: AppTab): String = when (tab) {
        AppTab.Chats -> chats
        AppTab.Approvals -> approvals
        AppTab.Machines -> machines
        AppTab.Settings -> settings
    }

    fun newChatModeLabel(mode: NewChatMode): String = when (mode) {
        NewChatMode.NewSession -> newSession
        NewChatMode.ExistingSession -> existingSession
    }

    companion object {
        val English = AppStrings(
            mobileControlSubtitle = "Mobile control for remote coding agents",
            settings = "Settings",
            updates = "Updates",
            language = "Language",
            languageSystem = "System",
            languageEnglish = "English",
            languageChinese = "中文",
            languageDescription = "System uses Chinese when the device language is Chinese; otherwise it uses English.",
            sessionLoadHistory = "Session load history",
            sessionLoadHistoryDescription = "Maximum number of recent messages to append when opening or resuming an existing ACP session.",
            sessionLoadHistoryLabel = "Recent messages",
            manageSettings = "Manage AgentLink app behavior and releases.",
            currentVersion = { "Current version: $it" },
            checking = "Checking...",
            checkForUpdates = "Check for updates",
            downloadVersion = { "Download $it" },
            updateInstallNote = "APK downloads open in the browser/system installer. Signed release APKs can update in-place after the signing key is configured.",
            feedback = "Feedback",
            feedbackDescription = "Request a new feature, report a bug, or get in touch if you would like to help develop AgentLink. GitHub Issues and email are both welcome.",
            openGitHubIssue = "Open GitHub Issue",
            developerContact = "Developer contact",
            emailDeveloper = "Email developer",
            noReleasesFound = "No releases found.",
            updateAvailable = { "Update $it is available." },
            latestVersion = "You're on the latest version.",
            updateCheckFailed = { "Update check failed: ${it.orUnknownError()}" },
            checkingForUpdates = "Checking for updates...",
            chats = "Chats",
            approvals = "Approvals",
            machines = "Machines",
            newChat = "New Chat",
            newSession = "New session",
            existingSession = "Existing session",
            chooseMachineWorkspaceAgent = "Choose Machine, Workspace, and Agent",
            tapCreateAgentSession = "Tap to create another Agent session",
            pairMachineFirst = "Pair a Machine first from the Machines tab.",
            noAgentDiscovered = "No Agent discovered on this Machine yet.",
            remoteWorkspacePath = "Remote Workspace path (optional)",
            remoteWorkspacePlaceholder = "Leave blank for remote home, or enter D:\\repos\\project-a",
            createChat = "Create Chat",
            loadingSessions = "Loading sessions...",
            loadSessions = "Load sessions",
            couldNotLoadSessions = { "Could not load sessions: ${it.orUnknownError()}" },
            noResumableSessions = "No resumable sessions",
            noResumableSessionsForAgent = "This Agent did not return resumable sessions for the selected Machine.",
            openSession = "Open Session",
            conversations = { "$it conversation${if (it == 1) "" else "s"}" },
            noChatsYet = "No Chats yet",
            createChatAfterPairing = "Create a Chat above after pairing a Machine.",
            messages = { "$it messages" },
            back = "Back",
            send = "Send",
            appendPrompt = "Add",
            queuedPrompts = { "$it queued" },
            removeQueuedPrompt = "Remove",
            prompt = "Prompt",
            current = "Current",
            close = "Close",
            on = "On",
            off = "Off",
            configOptionsNotLoaded = "Loading options from the agent. If this stays empty, the agent may not expose this config option yet.",
            resumeSession = "Resume session",
            loadingSessionsFrom = { "Loading sessions from $it..." },
            noResumableSessionsWorkspace = "No resumable sessions found for this Workspace.",
            agentActivity = "Agent activity",
            plan = "Plan",
            hide = "Hide",
            details = "Details",
            approvalsSubtitle = "Review commands, file changes, and risky actions before they run.",
            pending = { "$it pending" },
            noApprovalRequests = "No Approval requests",
            approvalRequestsAppear = "Agent requests that need your decision will appear here.",
            approve = "Approve",
            deny = "Deny",
            addMachine = "Add Machine",
            addMachineSubtitle = "Scan the bridge QR code, or paste the acpclient://pair link.",
            scanQr = "Scan QR",
            pairLink = "Pair Link",
            pairingLinkFallback = "Pairing link fallback",
            machinesPaired = { "$it paired" },
            noMachinesPaired = "No Machines paired",
            startBridgePairMachine = "Start the bridge, scan the QR code, then test the connection.",
            testConnection = "Test Connection",
            agents = "Agents",
            delete = "Delete",
            selected = "Selected",
            scanPairingQr = "Scan Pairing QR",
            pointCameraAtBridgeQr = "Point the camera at the bridge QR code.",
            cameraAccessNeeded = "Camera access is needed to scan pairing QR codes.",
            grantCameraPermission = "Grant Camera Permission",
            invalidPairingLink = "Invalid pairing link.",
            waitingBridgeApproval = { "Waiting for bridge approval on $it..." },
            machineOnline = { "$it is online." },
            pairedHealthFailed = { "Paired, but health check failed: ${it.orUnknownError()}" },
            pairingFailed = { "Pairing failed: ${it.orUnknownError()}" },
            machineUnavailable = "Machine is not available.",
            approvalRequired = { "Approval required · $it" },
            approvalRequiredTitle = "Approval required",
            bridgeWebSocketFailed = { "Bridge WebSocket failed: ${it.orUnknownError()}" },
            chatCreatedSystem = "Chat created. Workspace is selected per Chat, not at bridge startup.",
            chatStatusBusy = "Busy",
            chatStatusReady = "Ready",
            chatStatusOffline = "Offline",
            loadingExistingAcpSession = { "Loading existing ACP session $it." },
            openSessionFailed = { "Open session failed: ${it.orUnknownError()}" },
            resumeFailed = { "Resume failed: ${it.orUnknownError()}" },
            eventHistoryExpired = "Bridge event history expired and this Chat has no resumable ACP session.",
            setModel = "Set model",
            modelChangeFailed = { "Model change failed: ${it.orUnknownError()}" },
            connectionFailed = { "Connection failed: ${it.orUnknownError()}" },
            agentFinished = "Agent finished",
        )

        val Chinese = English.copy(
            mobileControlSubtitle = "远程 coding agents 的移动控制端",
            settings = "设置",
            updates = "更新",
            language = "语言",
            languageSystem = "跟随系统",
            languageEnglish = "English",
            languageChinese = "中文",
            languageDescription = "跟随系统时，系统语言为中文则使用中文，其他语言使用 English。",
            sessionLoadHistory = "Session load 历史",
            sessionLoadHistoryDescription = "打开或恢复已有 ACP session 时，最多追加最近 N 条消息。",
            sessionLoadHistoryLabel = "最近消息数量",
            manageSettings = "管理 AgentLink app 行为和版本更新。",
            currentVersion = { "当前版本：$it" },
            checking = "检查中...",
            checkForUpdates = "检查更新",
            downloadVersion = { "下载 $it" },
            updateInstallNote = "APK 下载会通过浏览器或系统安装器打开。配置签名密钥后，signed release APK 可以原地升级。",
            feedback = "反馈",
            feedbackDescription = "无论是希望新增功能、报告 Bug，还是想一起参与开发 AgentLink，都欢迎通过 GitHub Issues 或邮件联系。",
            openGitHubIssue = "打开 GitHub Issue",
            developerContact = "开发者联系方式",
            emailDeveloper = "发送邮件",
            noReleasesFound = "未找到 release。",
            updateAvailable = { "发现更新 $it。" },
            latestVersion = "当前已是最新版本。",
            updateCheckFailed = { "更新检查失败：${it.orUnknownErrorZh()}" },
            checkingForUpdates = "正在检查更新...",
            newChat = "新建 Chat",
            newSession = "新建 session",
            existingSession = "已有 session",
            chooseMachineWorkspaceAgent = "选择 Machine、Workspace 和 Agent",
            tapCreateAgentSession = "点击创建另一个 Agent session",
            pairMachineFirst = "请先在 Machines tab 配对一个 Machine。",
            noAgentDiscovered = "当前 Machine 还没有发现 Agent。",
            remoteWorkspacePath = "远程 Workspace 路径（可选）",
            remoteWorkspacePlaceholder = "留空使用远端 home，或输入 D:\\repos\\project-a",
            createChat = "创建 Chat",
            loadingSessions = "正在加载 sessions...",
            loadSessions = "加载 sessions",
            couldNotLoadSessions = { "无法加载 sessions：${it.orUnknownErrorZh()}" },
            noResumableSessions = "没有可恢复 sessions",
            noResumableSessionsForAgent = "所选 Machine 上的 Agent 没有返回可恢复 sessions。",
            openSession = "打开 Session",
            conversations = { "$it 个会话" },
            noChatsYet = "还没有 Chats",
            createChatAfterPairing = "配对 Machine 后，在上方创建 Chat。",
            messages = { "$it 条消息" },
            back = "返回",
            send = "发送",
            appendPrompt = "追加",
            queuedPrompts = { "$it 条待发送" },
            removeQueuedPrompt = "删除",
            prompt = "输入 Prompt",
            current = "当前",
            close = "关闭",
            on = "On",
            off = "Off",
            configOptionsNotLoaded = "正在从 Agent 加载选项。如果一直为空，说明当前 Agent 可能尚未暴露该配置项。",
            resumeSession = "恢复 session",
            loadingSessionsFrom = { "正在从 $it 加载 sessions..." },
            noResumableSessionsWorkspace = "当前 Workspace 没有可恢复 sessions。",
            agentActivity = "Agent activity",
            plan = "计划",
            hide = "收起",
            details = "详情",
            approvalsSubtitle = "在命令、文件变更和高风险操作执行前进行确认。",
            pending = { "$it 个 pending" },
            noApprovalRequests = "没有 Approval 请求",
            approvalRequestsAppear = "需要你决策的 Agent 请求会显示在这里。",
            approve = "批准",
            deny = "拒绝",
            addMachine = "添加 Machine",
            addMachineSubtitle = "扫描 bridge QR code，或粘贴 acpclient://pair link。",
            scanQr = "扫描 QR",
            pairLink = "配对 Link",
            pairingLinkFallback = "配对 link 备用输入",
            machinesPaired = { "$it 个已配对" },
            noMachinesPaired = "还没有配对 Machines",
            startBridgePairMachine = "启动 bridge，扫描 QR code，然后测试连接。",
            testConnection = "测试连接",
            delete = "删除",
            selected = "已选中",
            scanPairingQr = "扫描配对 QR",
            pointCameraAtBridgeQr = "将摄像头对准 bridge QR code。",
            cameraAccessNeeded = "需要摄像头权限来扫描配对 QR code。",
            grantCameraPermission = "授予摄像头权限",
            invalidPairingLink = "无效的配对 link。",
            waitingBridgeApproval = { "正在等待 $it 上的 bridge 确认..." },
            machineOnline = { "$it 已在线。" },
            pairedHealthFailed = { "已配对，但健康检查失败：${it.orUnknownErrorZh()}" },
            pairingFailed = { "配对失败：${it.orUnknownErrorZh()}" },
            machineUnavailable = "Machine 不可用。",
            approvalRequired = { "需要 Approval · $it" },
            approvalRequiredTitle = "需要 Approval",
            bridgeWebSocketFailed = { "Bridge WebSocket 失败：${it.orUnknownErrorZh()}" },
            chatCreatedSystem = "Chat 已创建。Workspace 按 Chat 选择，不绑定在 bridge 启动时。",
            chatStatusBusy = "忙碌",
            chatStatusReady = "空闲",
            chatStatusOffline = "断连",
            loadingExistingAcpSession = { "正在加载已有 ACP session $it。" },
            openSessionFailed = { "打开 session 失败：${it.orUnknownErrorZh()}" },
            resumeFailed = { "恢复失败：${it.orUnknownErrorZh()}" },
            eventHistoryExpired = "Bridge 事件历史已过期，并且此 Chat 没有可恢复的 ACP session。",
            setModel = "设置 model",
            modelChangeFailed = { "Model 修改失败：${it.orUnknownErrorZh()}" },
            connectionFailed = { "连接失败：${it.orUnknownErrorZh()}" },
            agentFinished = "Agent 已完成",
        )
    }
}

private fun String?.orUnknownError(): String = this?.takeIf { it.isNotBlank() } ?: "Unknown error"

private fun String?.orUnknownErrorZh(): String = this?.takeIf { it.isNotBlank() } ?: "未知错误"

private fun AppLanguageMode.resolveStrings(): AppStrings {
    return when (this) {
        AppLanguageMode.English -> AppStrings.English
        AppLanguageMode.Chinese -> AppStrings.Chinese
        AppLanguageMode.System -> if (Locale.getDefault().language.equals("zh", ignoreCase = true)) AppStrings.Chinese else AppStrings.English
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentLinkApp(
    incomingPairingLink: MutableState<String?>,
    incomingChatId: MutableState<String?>,
    appInForeground: State<Boolean>,
) {
    val context = LocalContext.current
    val machineStore = remember { MachineStore(context.applicationContext) }
    val chatStore = remember { ChatStore(context.applicationContext) }
    val appSettingsStore = remember { AppSettingsStore(context.applicationContext) }
    val bridgeClient = remember { BridgeClient() }
    val chatNotificationManager = remember { ChatNotificationManager(context.applicationContext) }
    val updateClient = remember { UpdateClient() }
    val parser = remember { PairingLinkParser() }
    val machines = remember { mutableStateListOf<Machine>() }
    val chats = remember { mutableStateListOf<Chat>() }
    val approvals = remember { mutableStateListOf<Approval>() }
    val busyChatIds = remember { mutableStateListOf<String>() }
    val unreadChatIds = remember { mutableStateListOf<String>() }
    val latestAgentPreviews = remember { mutableStateMapOf<String, String>() }
    val pendingLocalPromptStartEventIds = remember { mutableStateMapOf<String, Int>() }
    val authoritativeBusyEventIds = remember { mutableStateMapOf<String, Int>() }
    val activePromptOperationIds = remember { mutableStateMapOf<String, String>() }
    val chatConnections = remember { mutableStateMapOf<String, ChatConnection>() }
    val statusSynchronizedChatIds = remember { mutableStateListOf<String>() }
    val resyncingChatIds = remember { mutableStateListOf<String>() }
    val pendingResyncEventIds = remember { mutableStateMapOf<String, Int>() }
    val recoveredResyncMessages = remember { mutableStateMapOf<String, List<ChatMessage>>() }
    val resyncSnapshotChatIds = remember { mutableStateListOf<String>() }
    var selectedTab by remember { mutableStateOf(AppTab.Machines) }
    var selectedChatId by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var scannerOpen by remember { mutableStateOf(false) }
    var resumeDialogState by remember { mutableStateOf<ResumeDialogState?>(null) }
    var modelDialogState by remember { mutableStateOf<ModelDialogState?>(null) }
    var updateState by remember { mutableStateOf(UpdateUiState()) }
    var languageMode by remember { mutableStateOf(appSettingsStore.loadLanguageMode()) }
    var sessionLoadMessageLimit by remember { mutableStateOf(appSettingsStore.loadSessionLoadMessageLimit()) }
    val strings = languageMode.resolveStrings()
    val scope = rememberCoroutineScope()

    fun upsertMachine(machine: Machine) {
        val index = machines.indexOfFirst { it.id == machine.id }
        if (index >= 0) machines[index] = machine else machines.add(machine)
        machineStore.upsert(machine)
    }

    fun upsertChat(chat: Chat) {
        val index = chats.indexOfFirst { it.id == chat.id }
        if (index >= 0) chats[index] = chat else chats.add(chat)
        chatStore.upsert(chat)
    }

    fun handleSessionBinding(chatId: String, sessionId: String, resumable: Boolean) {
        val current = chats.firstOrNull { it.id == chatId } ?: return
        upsertChat(current.bindAcpSession(sessionId, resumable))
    }

    fun setChatUnread(chatId: String, unread: Boolean) {
        if (unread) {
            if (chatId !in unreadChatIds) unreadChatIds.add(chatId)
        } else {
            unreadChatIds.remove(chatId)
            chatNotificationManager.cancel(chatId)
        }
        chatStore.setUnread(chatId, unread)
    }

    fun openChat(chatId: String) {
        selectedTab = AppTab.Chats
        selectedChatId = chatId
        setChatUnread(chatId, false)
    }

    fun deleteChat(chat: Chat) {
        chats.removeAll { it.id == chat.id }
        chatStore.remove(chat.id)
        unreadChatIds.remove(chat.id)
        chatNotificationManager.cancel(chat.id)
        chatConnections.remove(chat.id)?.close()
        activePromptOperationIds.remove(chat.id)
        pendingLocalPromptStartEventIds.remove(chat.id)
        authoritativeBusyEventIds.remove(chat.id)
        pendingResyncEventIds.remove(chat.id)
        recoveredResyncMessages.remove(chat.id)
        resyncSnapshotChatIds.remove(chat.id)
        resyncingChatIds.remove(chat.id)
        statusSynchronizedChatIds.remove(chat.id)
        busyChatIds.remove(chat.id)
        if (selectedChatId == chat.id) selectedChatId = null
    }

    fun deleteMachine(machine: Machine) {
        machines.removeAll { it.id == machine.id }
        machineStore.remove(machine.id)
        if (statusMessage?.contains(machine.displayName) == true) statusMessage = null
    }

    fun createChat(machine: Machine, workspacePath: String, agent: Agent) {
        val path = workspacePath.trim().ifBlank { "~" }
        val workspaceName = if (path == "~") {
            "Home"
        } else {
            path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { path }
        }
        val chat = Chat(
            id = "chat_" + UUID.randomUUID(),
            title = "$workspaceName · ${agent.displayName}",
            machineId = machine.id,
            machineName = machine.displayName,
            workspaceId = workspaceName,
            workspaceName = workspaceName,
            workspacePath = path,
            agentId = agent.id,
            agentName = agent.displayName,
            createdAtMillis = System.currentTimeMillis(),
            messages = listOf(
                ChatMessage(
                    role = MessageRole.System,
                    text = strings.chatCreatedSystem,
                    timestampMillis = System.currentTimeMillis(),
                ),
            ),
        )
        upsertChat(chat)
        selectedChatId = chat.id
        selectedTab = AppTab.Chats
    }

    fun openExistingSession(machine: Machine, agent: Agent, session: AgentSessionInfo) {
        val path = session.cwd?.ifBlank { null } ?: "~"
        val workspaceName = if (path == "~") {
            "Home"
        } else {
            path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { path }
        }
        val chat = Chat(
            id = "chat_" + UUID.randomUUID(),
            title = session.title ?: "$workspaceName · ${agent.displayName}",
            machineId = machine.id,
            machineName = machine.displayName,
            workspaceId = workspaceName,
            workspaceName = workspaceName,
            workspacePath = path,
            agentId = agent.id,
            agentName = agent.displayName,
            createdAtMillis = System.currentTimeMillis(),
            acpSessionId = session.sessionId,
            acpSessionResumable = true,
            messages = listOf(
                ChatMessage(
                    role = MessageRole.System,
                    text = strings.loadingExistingAcpSession(session.sessionId),
                    timestampMillis = System.currentTimeMillis(),
                ),
            ),
        )
        upsertChat(chat)
        selectedChatId = chat.id
        selectedTab = AppTab.Chats
        scope.launch {
            bridgeClient.loadRecentSession(
                machine,
                chat.id,
                agent.id,
                path,
                session.sessionId,
                sessionLoadMessageLimit,
                onSession = { sessionId, resumable -> handleSessionBinding(chat.id, sessionId, resumable) },
            ).result
                .onSuccess { messages ->
                    val current = chats.firstOrNull { it.id == chat.id } ?: return@onSuccess
                    upsertChat(current.copy(messages = messages.ifEmpty { current.messages }))
                }
                .onFailure {
                    val current = chats.firstOrNull { current -> current.id == chat.id } ?: return@onFailure
                    upsertChat(current.withMessage(MessageRole.System, strings.openSessionFailed(it.message)))
                }
        }
    }

    fun loadExistingSessions(machine: Machine, agent: Agent, onResult: (Result<List<AgentSessionInfo>>) -> Unit) {
        scope.launch {
            onResult(bridgeClient.listSessions(machine, agent.id, ""))
        }
    }

    fun addApproval(chat: Chat, request: BridgeApprovalRequest? = null) {
        if (request != null && approvals.any { it.id == request.approvalId }) return
        approvals.add(
            Approval(
                id = request?.approvalId ?: "approval_" + UUID.randomUUID(),
                chatId = chat.id,
                chatTitle = chat.title,
                machineId = chat.machineId,
                machineName = chat.machineName,
                workspacePath = chat.workspacePath,
                action = request?.action ?: "run_command",
                summary = request?.summary ?: "Run test command in ${chat.workspacePath}",
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        selectedTab = AppTab.Approvals
    }

    fun appendChatEvent(chatId: String, message: ChatMessage) {
        val current = chats.firstOrNull { it.id == chatId } ?: return
        val mergedMessages = current.messages.mergeMessage(message)
        upsertChat(current.copy(messages = mergedMessages))
        if (message.role == MessageRole.Agent && message.kind == ChatMessageKind.Message) {
            mergedMessages.lastOrNull { it.role == MessageRole.Agent && it.kind == ChatMessageKind.Message }
                ?.text
                ?.takeIf { it.isNotBlank() }
                ?.let { latestAgentPreviews[chatId] = it }
        }

    }

    fun updateApproval(approval: Approval, status: ApprovalStatus) {
        val index = approvals.indexOfFirst { it.id == approval.id }
        if (index >= 0) approvals[index] = approval.copy(status = status)
        val machine = machines.firstOrNull { it.id == approval.machineId } ?: return
        scope.launch {
            bridgeClient.sendApprovalDecision(machine, approval.id, if (status == ApprovalStatus.Approved) "approved" else "denied").result
        }
    }

    fun updateChatStatus(
        chatId: String,
        status: String,
        eventId: Int? = null,
        queuedCount: Int = 0,
        operationId: String = "",
        authoritativeSnapshot: Boolean = false,
    ) {
        val pendingStartEventId = pendingLocalPromptStartEventIds[chatId]
        if (!authoritativeSnapshot && !shouldApplyChatStatus(status, activePromptOperationIds[chatId])) {
            return
        }
        if (status == "idle" && chatId in pendingLocalPromptStartEventIds) {
            return
        }
        if (
            status == "idle" &&
            chats.firstOrNull { it.id == chatId }?.queuedPrompts?.any { !it.removing } == true
        ) {
            return
        }
        if (
            !authoritativeSnapshot &&
            (status == "busy" || status == "waitingApproval") &&
            pendingStartEventId != null &&
            (eventId == null || eventId <= pendingStartEventId)
        ) {
            return
        }
        if (
            !authoritativeSnapshot &&
            status == "idle" &&
            eventId != null &&
            eventId <= (authoritativeBusyEventIds[chatId] ?: 0)
        ) {
            return
        }
        when (status) {
            "busy", "waitingApproval" -> {
                if (chatId !in busyChatIds) busyChatIds.add(chatId)
                if (operationId.isNotBlank()) {
                    activePromptOperationIds[chatId] = operationId
                }
                pendingLocalPromptStartEventIds.remove(chatId)
                if (eventId != null) {
                    authoritativeBusyEventIds[chatId] = maxOf(authoritativeBusyEventIds[chatId] ?: 0, eventId)
                }
            }
            "idle", "failed" -> {
                busyChatIds.remove(chatId)
                pendingLocalPromptStartEventIds.remove(chatId)
                authoritativeBusyEventIds.remove(chatId)
                val hasRemovalTombstones =
                    chats.firstOrNull { it.id == chatId }?.queuedPrompts?.any { it.removing } == true
                if (selectedChatId != chatId && (status == "failed" || !hasRemovalTombstones)) {
                    chatConnections.remove(chatId)?.close()
                }
            }
            "disconnected" -> Unit
        }
    }

    fun handlePromptStarted(chatId: String, operationId: String, content: String) {
        activePromptOperationIds[chatId] = operationId
        val chat = chats.firstOrNull { it.id == chatId } ?: return
        upsertChat(chat.startQueuedPrompt(operationId, content, System.currentTimeMillis()))
    }

    fun handlePromptAccepted(chatId: String, operationId: String, state: String, content: String) {
        val wasActivePrompt = activePromptOperationIds[chatId] == operationId
        when (state) {
            "", "starting", "running" -> activePromptOperationIds[chatId] = operationId
            "completed", "failed", "cancelled" -> {
                if (activePromptOperationIds[chatId] == operationId) {
                    activePromptOperationIds.remove(chatId)
                }
            }
        }
        val chat = chats.firstOrNull { it.id == chatId } ?: return
        val accepted = chat.acceptPrompt(operationId, state, content, System.currentTimeMillis())
        upsertChat(accepted)
        if (state == "cancelled" && shouldClearBusyAfterCancellation(wasActivePrompt, accepted.queuedPrompts)) {
            busyChatIds.remove(chatId)
            pendingLocalPromptStartEventIds.remove(chatId)
            authoritativeBusyEventIds.remove(chatId)
        }
    }

    fun handleOperationDone(
        chatId: String,
        operationType: String,
        status: String,
        operationId: String = "",
        queueRemaining: Int = 0,
        allowAttention: Boolean = true,
    ) {
        if (operationType != "chat.prompt") return
        val wasActivePrompt = activePromptOperationIds[chatId] == operationId
        if (isTerminalPromptStatus(status) && wasActivePrompt) {
            activePromptOperationIds.remove(chatId)
        }
        val current = chats.firstOrNull { it.id == chatId }
        val finished = current?.finishPrompt(operationId, status, System.currentTimeMillis())
        if (finished != null) {
            upsertChat(
                if (status == "completed" && finished.acpSessionId != null) {
                    finished.bindAcpSession(finished.acpSessionId, resumable = true)
                } else {
                    finished
                },
            )
        }
        if (status == "cancelled") {
            if (
                finished != null &&
                shouldClearBusyAfterCancellation(wasActivePrompt, finished.queuedPrompts)
            ) {
                busyChatIds.remove(chatId)
                pendingLocalPromptStartEventIds.remove(chatId)
                authoritativeBusyEventIds.remove(chatId)
            }
            if (
                finished?.queuedPrompts?.isEmpty() == true &&
                chatId !in busyChatIds &&
                activePromptOperationIds[chatId] == null &&
                selectedChatId != chatId
            ) {
                chatConnections.remove(chatId)?.close()
            }
            return
        }
        if (!isFinalPromptCompletion(status, queueRemaining)) return
        if (!allowAttention) return
        val chat = chats.firstOrNull { it.id == chatId } ?: return
        val preview = chatCompletionPreview(
            latestAgentPreview = latestAgentPreviews.remove(chatId),
            persistedAgentMessages = chat.messages
                .filter { it.role == MessageRole.Agent && it.kind == ChatMessageKind.Message }
                .map { it.text },
            fallback = strings.agentFinished,
        ).take(240)
        val attention = chatCompletionAttention(
            appInForeground = appInForeground.value,
            chatIsOpen = selectedTab == AppTab.Chats && selectedChatId == chatId,
        )
        if (attention.markUnread) {
            setChatUnread(chatId, true)
        }
        if (attention.showNotification) {
            chatNotificationManager.showCompletion(chatId, chat.title, preview)
        }
    }

    fun deleteApproval(approval: Approval) {
        if (approval.status == ApprovalStatus.Pending) {
            updateApproval(approval, ApprovalStatus.Denied)
        }
        approvals.removeAll { it.id == approval.id }
    }

    fun showResumeDialog(chat: Chat) {
        val machine = machines.firstOrNull { it.id == chat.machineId }
        if (machine == null) {
            upsertChat(chat.withMessage(MessageRole.System, strings.machineUnavailable))
            return
        }
        resumeDialogState = ResumeDialogState(chat = chat, sessions = null, error = null)
        scope.launch {
            bridgeClient.listSessions(machine, chat.agentId, chat.workspacePath)
                .onSuccess { sessions ->
                    resumeDialogState = ResumeDialogState(chat = chat, sessions = sessions, error = null)
                }
                .onFailure {
                    resumeDialogState = ResumeDialogState(chat = chat, sessions = emptyList(), error = it.message)
                }
        }
    }

    fun loadResumeSession(chat: Chat, session: AgentSessionInfo) {
        val machine = machines.firstOrNull { it.id == chat.machineId }
        if (machine == null) {
            upsertChat(chat.withMessage(MessageRole.System, strings.machineUnavailable))
            return
        }
        resumeDialogState = null
        val loading = chat.withActivity(
            title = strings.resumeSession,
            summary = session.title ?: session.sessionId,
            details = "sessionId=${session.sessionId}\ncwd=${session.cwd.orEmpty()}\nupdatedAt=${session.updatedAt.orEmpty()}",
        )
        upsertChat(loading)
        scope.launch {
            val bound = loading.bindAcpSession(session.sessionId, resumable = true)
            upsertChat(bound)
            bridgeClient.loadRecentSession(
                machine,
                chat.id,
                chat.agentId,
                chat.workspacePath,
                session.sessionId,
                sessionLoadMessageLimit,
                onSession = { sessionId, resumable -> handleSessionBinding(chat.id, sessionId, resumable) },
            ).result
                .onSuccess { messages ->
                    val current = chats.firstOrNull { it.id == chat.id } ?: return@onSuccess
                    upsertChat(current.copy(messages = current.messages + messages))
                }
                .onFailure {
                    val current = chats.firstOrNull { current -> current.id == chat.id } ?: return@onFailure
                    upsertChat(current.withMessage(MessageRole.System, strings.resumeFailed(it.message)))
                }
        }
    }

    fun showModelDialog(chat: Chat, option: ConfigOption) {
        modelDialogState = ModelDialogState(chat = chat, option = option)
        val machine = machines.firstOrNull { it.id == chat.machineId } ?: return
        scope.launch {
            bridgeClient.refreshConfigOptions(
                machine,
                chat.id,
                chat.agentId,
                chat.workspacePath,
                chat.acpSessionId,
                chat.acpSessionResumable,
                onSession = { sessionId, resumable -> handleSessionBinding(chat.id, sessionId, resumable) },
            ).result
                .onSuccess { events ->
                    val current = chats.firstOrNull { it.id == chat.id } ?: return@onSuccess
                    val refreshed = current.copy(messages = events.fold(current.messages) { messages, event -> messages.mergeMessage(event) })
                    upsertChat(refreshed)
                    val refreshedOption = when {
                        option.id.normalizedKey() == "model" -> refreshed.modelConfigOption()
                        option.id.normalizedKey() in ALLOW_ALL_KEYS -> refreshed.allowAllConfigOption()
                        else -> null
                    }
                    if (refreshedOption != null) {
                        modelDialogState = ModelDialogState(chat = refreshed, option = refreshedOption)
                    }
                }
                .onFailure {
                    val current = chats.firstOrNull { current -> current.id == chat.id } ?: return@onFailure
                    upsertChat(current.withMessage(MessageRole.System, strings.modelChangeFailed(it.message)))
                }
        }
    }

    fun setModel(chat: Chat, option: ConfigOption, value: ConfigOptionValue) {
        val machine = machines.firstOrNull { it.id == chat.machineId }
        if (machine == null) {
            upsertChat(chat.withMessage(MessageRole.System, strings.machineUnavailable))
            return
        }
        modelDialogState = null
        val changing = chat.withActivity(
            title = strings.setModel,
            summary = value.name,
            details = "configId=${option.id}\nvalue=${value.value}\n${value.description.orEmpty()}",
        )
        upsertChat(changing)
        scope.launch {
            bridgeClient.setConfigOption(
                machine,
                chat.id,
                chat.agentId,
                chat.workspacePath,
                option.id,
                value.value,
                chat.acpSessionId,
                chat.acpSessionResumable,
                onSession = { sessionId, resumable -> handleSessionBinding(chat.id, sessionId, resumable) },
            ).result
                .onSuccess { events ->
                    val current = chats.firstOrNull { it.id == chat.id } ?: return@onSuccess
                    upsertChat(current.copy(messages = current.messages + events))
                }
                .onFailure {
                    val current = chats.firstOrNull { current -> current.id == chat.id } ?: return@onFailure
                    upsertChat(current.withMessage(MessageRole.System, strings.modelChangeFailed(it.message)))
                }
        }
    }

    fun pairFromLink(link: String) {
        val payload = parser.parse(link).getOrElse {
            statusMessage = it.message ?: strings.invalidPairingLink
            return
        }
        statusMessage = strings.waitingBridgeApproval(payload.machineName)
        scope.launch {
            bridgeClient.redeemPairing(payload)
                .onSuccess { machine ->
                    upsertMachine(machine)
                    bridgeClient.fetchMachineDetails(machine)
                        .onSuccess {
                            upsertMachine(it)
                            statusMessage = strings.machineOnline(it.displayName)
                        }
                        .onFailure { statusMessage = strings.pairedHealthFailed(it.message) }
                }
                .onFailure { statusMessage = strings.pairingFailed(it.message) }
        }
    }

    fun checkForUpdate(manual: Boolean) {
        updateState = updateState.copy(checking = true, status = if (manual) UpdateStatus.Checking else updateState.status, errorMessage = null)
        scope.launch {
            updateClient.checkForUpdate()
                .onSuccess { update ->
                    updateState = UpdateUiState(
                        checking = false,
                        update = update,
                        status = when {
                            update == null -> UpdateStatus.NoReleases
                            update.isNewer -> UpdateStatus.UpdateAvailable
                            else -> UpdateStatus.Latest
                        },
                    )
                }
                .onFailure {
                    updateState = updateState.copy(checking = false, status = UpdateStatus.Failed, errorMessage = it.message)
                }
        }
    }

    fun openUpdate(update: AppUpdate) {
        val url = update.apkUrl ?: update.releaseUrl
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    fun openFeedbackIssue() {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FEEDBACK_ISSUES_URL)))
    }

    fun emailDeveloper() {
        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$DEVELOPER_EMAIL")))
    }

    fun finalizeRecoveredHistory(chatId: String) {
        val messages = recoveredResyncMessages[chatId] ?: return
        if (chatId !in resyncSnapshotChatIds) return
        val latest = chats.firstOrNull { it.id == chatId } ?: return
        val checkpoint = pendingResyncEventIds.remove(chatId)
        val recovered = latest.copy(
            messages = reconcileRecentSessionMessages(latest.messages, messages),
            bridgeResyncRequired = false,
        )
        upsertChat(if (checkpoint == null) recovered else recovered.recordBridgeEventId(checkpoint))
        recoveredResyncMessages.remove(chatId)
        resyncSnapshotChatIds.remove(chatId)
    }

    fun recoverTruncatedHistory(chatId: String) {
        if (chatId in resyncingChatIds) return
        val current = chats.firstOrNull { it.id == chatId } ?: return
        recoveredResyncMessages.remove(chatId)
        resyncSnapshotChatIds.remove(chatId)
        activePromptOperationIds.remove(chatId)
        pendingLocalPromptStartEventIds.remove(chatId)
        authoritativeBusyEventIds.remove(chatId)
        val currentMachine = machines.firstOrNull { it.id == current.machineId }
        val sessionId = current.acpSessionId
        if (currentMachine == null || sessionId == null) {
            val blocked = current.copy(bridgeResyncRequired = true)
            if (blocked.messages.lastOrNull()?.text != strings.eventHistoryExpired) {
                upsertChat(blocked.withMessage(MessageRole.System, strings.eventHistoryExpired))
            } else if (blocked != current) {
                upsertChat(blocked)
            }
            return
        }
        upsertChat(current.copy(bridgeResyncRequired = true))
        resyncingChatIds.add(chatId)
        scope.launch {
            try {
                bridgeClient.loadRecentSession(
                    currentMachine,
                    current.id,
                    current.agentId,
                    current.workspacePath,
                    sessionId,
                    sessionLoadMessageLimit,
                    onSession = { restoredSessionId, resumable ->
                        handleSessionBinding(current.id, restoredSessionId, resumable)
                    },
                ).result
                    .onSuccess { messages ->
                        recoveredResyncMessages[current.id] = messages
                        finalizeRecoveredHistory(current.id)
                    }
                    .onFailure {
                        val latest = chats.firstOrNull { it.id == current.id } ?: return@onFailure
                        val failureMessage = strings.resumeFailed(it.message)
                        if (latest.messages.lastOrNull()?.text != failureMessage) {
                            upsertChat(latest.withMessage(MessageRole.System, failureMessage))
                        }
                        resyncingChatIds.remove(current.id)
                        recoveredResyncMessages.remove(current.id)
                        resyncSnapshotChatIds.remove(current.id)
                        chatConnections.remove(current.id)?.close()
                        statusSynchronizedChatIds.remove(current.id)
                    }
            } finally {
                resyncingChatIds.remove(current.id)
            }
        }
    }

    fun ensureChatConnection(chat: Chat): Boolean {
        if (chat.id in chatConnections) return true
        val machine = machines.firstOrNull { it.id == chat.machineId } ?: return false
        lateinit var connection: ChatConnection
        connection = bridgeClient.openChatConnection(
            machine = machine,
            chatId = chat.id,
            agentId = chat.agentId,
            workspacePath = chat.workspacePath,
            lastEventId = chat.lastBridgeEventId,
            lastEventGeneration = chat.bridgeEventGeneration,
            sessionId = chat.acpSessionId,
            sessionResumable = chat.acpSessionResumable,
            queuedPrompts = chat.queuedPrompts,
            onMessage = { event, isReplay ->
                if (chatConnections[chat.id] !== connection) return@openChatConnection
                val current = chats.firstOrNull { it.id == chat.id }
                if (current?.bridgeResyncRequired != true || !isReplay) {
                    appendChatEvent(chat.id, event)
                }
            },
            onApproval = { request, _ ->
                if (chatConnections[chat.id] !== connection) return@openChatConnection
                val current = chats.firstOrNull { it.id == chat.id } ?: return@openChatConnection
                appendChatEvent(
                    chat.id,
                    ChatMessage(
                        role = MessageRole.Agent,
                        text = strings.approvalRequired(request.summary),
                        timestampMillis = System.currentTimeMillis(),
                        kind = ChatMessageKind.Activity,
                        title = strings.approvalRequiredTitle,
                        details = request.details,
                        activityId = request.approvalId,
                    ),
                )
                addApproval(current, request)
            },
            onStatus = statusCallback@{ status, eventId, queuedCount, operationId, isSnapshot ->
                if (chatConnections[chat.id] !== connection) return@statusCallback
                if (chat.id !in statusSynchronizedChatIds) {
                    if (!isSnapshot) return@statusCallback
                }
                updateChatStatus(
                    chat.id,
                    status,
                    eventId,
                    queuedCount,
                    operationId,
                    authoritativeSnapshot = isSnapshot,
                )
                if (isSnapshot) {
                    if (chats.firstOrNull { it.id == chat.id }?.bridgeResyncRequired == true) {
                        if (chat.id !in resyncSnapshotChatIds) resyncSnapshotChatIds.add(chat.id)
                        finalizeRecoveredHistory(chat.id)
                    }
                    if (chat.id !in statusSynchronizedChatIds) statusSynchronizedChatIds.add(chat.id)
                }
            },
            onPromptAccepted = { operationId, state, content, _ ->
                if (chatConnections[chat.id] !== connection) return@openChatConnection
                handlePromptAccepted(chat.id, operationId, state, content)
            },
            onPromptStarted = { operationId, content, _ ->
                if (chatConnections[chat.id] !== connection) return@openChatConnection
                handlePromptStarted(chat.id, operationId, content)
            },
            onOperationDone = { operationType, status, operationId, queueRemaining, isReplay ->
                if (chatConnections[chat.id] !== connection) return@openChatConnection
                handleOperationDone(
                    chat.id,
                    operationType,
                    status,
                    operationId,
                    queueRemaining,
                    allowAttention = !isReplay,
                )
            },
            onSession = { sessionId, resumable, _ ->
                if (chatConnections[chat.id] !== connection) return@openChatConnection
                handleSessionBinding(chat.id, sessionId, resumable)
            },
            onEventId = {
                if (chatConnections[chat.id] !== connection) return@openChatConnection
                val current = chats.firstOrNull { current -> current.id == chat.id } ?: return@openChatConnection
                if (current.bridgeResyncRequired) {
                    pendingResyncEventIds[chat.id] = maxOf(pendingResyncEventIds[chat.id] ?: 0, it)
                    return@openChatConnection
                }
                val checkpointed = current.recordBridgeEventId(it)
                if (checkpointed != current) upsertChat(checkpointed)
            },
            onEventGeneration = { generation, checkpointReset ->
                if (chatConnections[chat.id] !== connection) return@openChatConnection
                val current = chats.firstOrNull { current -> current.id == chat.id } ?: return@openChatConnection
                if (checkpointReset || current.bridgeEventGeneration != generation) {
                    pendingResyncEventIds.remove(chat.id)
                    activePromptOperationIds.remove(chat.id)
                    pendingLocalPromptStartEventIds.remove(chat.id)
                    authoritativeBusyEventIds.remove(chat.id)
                }
                val rebound = current.bindBridgeEventGeneration(generation, checkpointReset)
                if (rebound != current) upsertChat(rebound)
                if (current.bridgeResyncRequired) recoverTruncatedHistory(chat.id)
            },
            onResyncRequired = {
                if (chatConnections[chat.id] === connection) recoverTruncatedHistory(chat.id)
            },
            onFailure = {
                if (chatConnections[chat.id] === connection) {
                    chatConnections.remove(chat.id)
                    statusSynchronizedChatIds.remove(chat.id)
                    updateChatStatus(chat.id, "disconnected")
                }
            },
        )
        chatConnections[chat.id] = connection
        return true
    }

    LaunchedEffect(Unit) {
        machines.clear()
        machines.addAll(machineStore.load())
        val storedChats = chatStore.load()
        chats.clear()
        chats.addAll(storedChats)
        busyChatIds.clear()
        busyChatIds.addAll(
            storedChats
                .filter { chat -> chat.queuedPrompts.any { queued -> !queued.removing } }
                .map { it.id },
        )
        unreadChatIds.clear()
        unreadChatIds.addAll(chatStore.loadUnreadChatIds().filter { unreadChatId -> chats.any { it.id == unreadChatId } })
        checkForUpdate(manual = false)
    }

    val chatConnectionKeys = chats.map { chat ->
        buildString {
            append(chat.id)
            append('|')
            append(chat.machineId)
            append('|')
            append(chat.agentId)
            append('|')
            append(chat.workspacePath)
            append('|')
            append(chat.acpSessionId.orEmpty())
            append('|')
            chat.queuedPrompts.forEach { queued ->
                append(queued.operationId)
                append(':')
                append(queued.removing)
                append(',')
            }
        }
    }
    val machineConnectionKeys = machines.map { "${it.id}|${it.endpoint}|${it.deviceToken}" }
    LaunchedEffect(chatConnectionKeys, machineConnectionKeys, statusSynchronizedChatIds.toList()) {
        var retryDelayMillis = 1_000L
        while (true) {
            val unsynchronizedChats = chats.filter { it.id !in statusSynchronizedChatIds }
            if (unsynchronizedChats.isEmpty()) break
            unsynchronizedChats.forEach(::ensureChatConnection)
            delay(retryDelayMillis)
            retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(30_000L)
        }
    }

    LaunchedEffect(incomingPairingLink.value) {
        incomingPairingLink.value?.let { link ->
            selectedTab = AppTab.Machines
            pairFromLink(link)
            incomingPairingLink.value = null
        }
    }

    LaunchedEffect(incomingChatId.value, chats.size) {
        incomingChatId.value?.takeIf { chatId -> chats.any { it.id == chatId } }?.let { chatId ->
            openChat(chatId)
            incomingChatId.value = null
        }
    }

    val queuedPromptRemovalKeys = chats.flatMap { chat ->
        chat.queuedPrompts
            .filter { it.removing }
            .map { queued -> "${chat.id}:${queued.operationId}" }
    }
    LaunchedEffect(queuedPromptRemovalKeys, machines.toList()) {
        var retryDelayMillis = 1_000L
        while (true) {
            val pendingRemovals = chats.flatMap { chat ->
                chat.queuedPrompts
                    .filter { it.removing }
                    .map { queued -> chat to queued }
            }
            if (pendingRemovals.isEmpty()) break
            pendingRemovals.forEach { (chat, queued) ->
                val machine = machines.firstOrNull { it.id == chat.machineId } ?: return@forEach
                bridgeClient.removeQueuedPrompt(machine, chat.id, queued.operationId).result
                    .onSuccess { status ->
                        when (status) {
                            "cancelled", "already_started" -> handleOperationDone(
                                chatId = chat.id,
                                operationType = "chat.prompt",
                                status = status,
                                operationId = queued.operationId,
                                queueRemaining = 0,
                            )
                        }
                    }
            }
            delay(retryDelayMillis)
            retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(30_000L)
        }
    }

    LaunchedEffect(selectedTab, selectedChatId, appInForeground.value) {
        if (appInForeground.value && selectedTab == AppTab.Chats) {
            selectedChatId?.let { setChatUnread(it, false) }
        }
    }

    MaterialTheme {
        CompositionLocalProvider(LocalAppStrings provides strings) {
        val activeChat = chats.firstOrNull { it.id == selectedChatId }
        val removingPromptIds = activeChat?.queuedPrompts
            ?.filter { it.removing }
            ?.map { it.operationId }
            .orEmpty()
        DisposableEffect(
            activeChat?.id,
            activeChat?.machineId,
            activeChat?.agentId,
            activeChat?.workspacePath,
            removingPromptIds,
        ) {
            if (activeChat == null) {
                onDispose { }
            } else {
                val machine = machines.firstOrNull { it.id == activeChat.machineId }
                if (machine == null) {
                    onDispose { }
                } else {
                    ensureChatConnection(activeChat)
                    onDispose {
                        if (activeChat.id !in busyChatIds) {
                            chatConnections.remove(activeChat.id)?.close()
                        }
                    }
                }
            }
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            if (scannerOpen) {
                QrScannerScreen(
                    onLinkScanned = { link ->
                        scannerOpen = false
                        selectedTab = AppTab.Machines
                        pairFromLink(link)
                    },
                    onClose = { scannerOpen = false },
                )
            } else {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text("AgentLink", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        strings.mobileControlSubtitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            AppTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    label = null,
                                    icon = {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(0.dp),
                                        ) {
                                            Text(tab.icon, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                            Text(strings.tabLabel(tab), style = MaterialTheme.typography.labelSmall)
                                        }
                                    },
                                )
                            }
                        }
                    },
                ) { padding ->
                    when (selectedTab) {
                        AppTab.Chats -> ChatsScreen(
                            padding = padding,
                            machines = machines,
                            chats = chats,
                            busyChatIds = busyChatIds.toSet(),
                            unreadChatIds = unreadChatIds.toSet(),
                            selectedChatId = selectedChatId,
                            onCreateChat = ::createChat,
                            onOpenExistingSession = ::openExistingSession,
                            onLoadExistingSessions = ::loadExistingSessions,
                            onOpenChat = { openChat(it.id) },
                            onDeleteChat = ::deleteChat,
                            onBackToList = { selectedChatId = null },
                            onResume = ::showResumeDialog,
                            onModel = ::showModelDialog,
                            onRemoveQueuedPrompt = { chat, operationId ->
                                val current = chats.firstOrNull { it.id == chat.id } ?: chat
                                if (current.queuedPrompts.none { it.operationId == operationId }) return@ChatsScreen
                                val machine = machines.firstOrNull { it.id == chat.machineId }
                                if (machine == null) {
                                    upsertChat(current.withMessage(MessageRole.System, strings.machineUnavailable))
                                    return@ChatsScreen
                                }
                                upsertChat(current.markQueuedPromptRemoving(operationId))
                                if (chatConnections[chat.id]?.removeQueuedPrompt(operationId) != true) {
                                    chatConnections.remove(chat.id)?.close()
                                }
                            },
                            onSendMessage = { chat, message ->
                                val machine = machines.firstOrNull { it.id == chat.machineId }
                                if (machine == null) {
                                    upsertChat(chat.withMessage(MessageRole.System, strings.machineUnavailable))
                                } else {
                                    val operationId = "op_" + UUID.randomUUID()
                                    pendingLocalPromptStartEventIds[chat.id] = chat.lastBridgeEventId
                                    if (chat.id !in activePromptOperationIds) {
                                        activePromptOperationIds[chat.id] = operationId
                                    }
                                    if (chat.id !in busyChatIds) busyChatIds.add(chat.id)
                                    val updated = chat.copy(
                                        queuedPrompts = chat.queuedPrompts + QueuedPrompt(
                                            operationId = operationId,
                                            text = message,
                                            createdAtMillis = System.currentTimeMillis(),
                                        ),
                                    )
                                    upsertChat(updated)
                                    latestAgentPreviews.remove(chat.id)
                                    val activeConnection = chatConnections[chat.id]
                                    val sent = activeConnection?.sendPrompt(
                                        operationId,
                                        chat.agentId,
                                        chat.workspacePath,
                                        message,
                                        chat.acpSessionId,
                                        chat.acpSessionResumable,
                                    ) == true
                                    if (!sent) {
                                        if (activeConnection != null) {
                                            chatConnections.remove(chat.id)
                                            statusSynchronizedChatIds.remove(chat.id)
                                            activeConnection.close()
                                        }
                                        ensureChatConnection(updated)
                                    }
                                }
                            },
                        )
                        AppTab.Approvals -> ApprovalsScreen(padding, approvals, ::updateApproval, ::deleteApproval)
                        AppTab.Machines -> MachinesScreen(
                            padding = padding,
                            machines = machines,
                            statusMessage = statusMessage,
                            onPairLink = ::pairFromLink,
                            onScanQr = { scannerOpen = true },
                            onRefreshMachine = { machine ->
                                scope.launch {
                                    bridgeClient.fetchMachineDetails(machine)
                                        .onSuccess {
                                            upsertMachine(it)
                                            statusMessage = strings.machineOnline(it.displayName)
                                        }
                                        .onFailure {
                                            upsertMachine(machine.copy(connectionState = ConnectionState.Offline))
                                            statusMessage = strings.connectionFailed(it.message)
                                        }
                                }
                            },
                            onDeleteMachine = ::deleteMachine,
                        )
                        AppTab.Settings -> SettingsScreen(
                            padding = padding,
                            updateState = updateState,
                            languageMode = languageMode,
                            onLanguageModeChange = {
                                languageMode = it
                                appSettingsStore.saveLanguageMode(it)
                            },
                            sessionLoadMessageLimit = sessionLoadMessageLimit,
                            onSessionLoadMessageLimitChange = {
                                sessionLoadMessageLimit = it
                                appSettingsStore.saveSessionLoadMessageLimit(it)
                            },
                            onCheckForUpdate = { checkForUpdate(manual = true) },
                            onOpenUpdate = ::openUpdate,
                            onOpenFeedbackIssue = ::openFeedbackIssue,
                            onEmailDeveloper = ::emailDeveloper,
                        )
                    }

                }
            }
        }

        resumeDialogState?.let { state ->
            ResumeDialog(
                state = state,
                onDismiss = { resumeDialogState = null },
                onSelect = { loadResumeSession(state.chat, it) },
            )
        }
        modelDialogState?.let { state ->
            ModelDialog(
                state = state,
                onDismiss = { modelDialogState = null },
                onSelect = { setModel(state.chat, state.option, it) },
            )
        }
        }
    }
}

@Composable
private fun SettingsScreen(
    padding: PaddingValues,
    updateState: UpdateUiState,
    languageMode: AppLanguageMode,
    onLanguageModeChange: (AppLanguageMode) -> Unit,
    sessionLoadMessageLimit: Int,
    onSessionLoadMessageLimitChange: (Int) -> Unit,
    onCheckForUpdate: () -> Unit,
    onOpenUpdate: (AppUpdate) -> Unit,
    onOpenFeedbackIssue: () -> Unit,
    onEmailDeveloper: () -> Unit,
) {
    val strings = LocalAppStrings.current
    var sessionLoadLimitText by remember(sessionLoadMessageLimit) { mutableStateOf(sessionLoadMessageLimit.toString()) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            PageHero(
                title = strings.settings,
                subtitle = strings.manageSettings,
                metric = "v${BuildConfig.VERSION_NAME}",
            )
        }
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings.language, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(strings.languageDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppLanguageMode.entries.forEach { mode ->
                            FilterChip(
                                selected = languageMode == mode,
                                onClick = { onLanguageModeChange(mode) },
                                label = {
                                    Text(
                                        when (mode) {
                                            AppLanguageMode.System -> strings.languageSystem
                                            AppLanguageMode.English -> strings.languageEnglish
                                            AppLanguageMode.Chinese -> strings.languageChinese
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings.sessionLoadHistory, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(strings.sessionLoadHistoryDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = sessionLoadLimitText,
                        onValueChange = { raw ->
                            val digits = raw.filter { it.isDigit() }.take(4)
                            sessionLoadLimitText = digits
                            digits.toIntOrNull()?.takeIf { it > 0 }?.let(onSessionLoadMessageLimitChange)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(strings.sessionLoadHistoryLabel) },
                    )
                }
            }
        }
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings.updates, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(strings.currentVersion(BuildConfig.VERSION_NAME), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    updateState.localizedMessage(strings)?.let {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)) {
                            Text(it, modifier = Modifier.padding(12.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = !updateState.checking, onClick = onCheckForUpdate) {
                            Text(if (updateState.checking) strings.checking else strings.checkForUpdates)
                        }
                        updateState.update?.takeIf { it.isNewer }?.let { update ->
                            OutlinedButton(onClick = { onOpenUpdate(update) }) {
                                Text(strings.downloadVersion(update.version))
                            }
                        }
                    }
                    Text(
                        strings.updateInstallNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings.feedback, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        strings.feedbackDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onOpenFeedbackIssue) {
                        Text(strings.openGitHubIssue)
                    }
                    Text(
                        "${strings.developerContact}: $DEVELOPER_EMAIL",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onEmailDeveloper) {
                        Text(strings.emailDeveloper)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatsScreen(
    padding: PaddingValues,
    machines: List<Machine>,
    chats: List<Chat>,
    busyChatIds: Set<String>,
    unreadChatIds: Set<String>,
    selectedChatId: String?,
    onCreateChat: (Machine, String, Agent) -> Unit,
    onOpenExistingSession: (Machine, Agent, AgentSessionInfo) -> Unit,
    onLoadExistingSessions: (Machine, Agent, (Result<List<AgentSessionInfo>>) -> Unit) -> Unit,
    onOpenChat: (Chat) -> Unit,
    onDeleteChat: (Chat) -> Unit,
    onBackToList: () -> Unit,
    onResume: (Chat) -> Unit,
    onModel: (Chat, ConfigOption) -> Unit,
    onRemoveQueuedPrompt: (Chat, String) -> Unit,
    onSendMessage: (Chat, String) -> Unit,
) {
    val strings = LocalAppStrings.current
    val selectedChat = chats.firstOrNull { it.id == selectedChatId }
    if (selectedChat != null) {
        val selectedMachineState = machines.firstOrNull { it.id == selectedChat.machineId }?.connectionState ?: ConnectionState.Unknown
        ChatDetailScreen(
            padding = padding,
            chat = selectedChat,
            isBusy = selectedChat.id in busyChatIds,
            connectionState = selectedMachineState,
            onBack = onBackToList,
            onSendMessage = { onSendMessage(selectedChat, it) },
            onRemoveQueuedPrompt = { onRemoveQueuedPrompt(selectedChat, it) },
            onCommand = { command ->
                when (command.name) {
                    BUILT_IN_MODEL_COMMAND.name -> onModel(selectedChat, selectedChat.modelConfigOption() ?: fallbackModelConfigOption())
                    BUILT_IN_RESUME_COMMAND.name -> onResume(selectedChat)
                    BUILT_IN_ALLOW_ALL_COMMAND.name -> onModel(selectedChat, selectedChat.allowAllConfigOption() ?: fallbackAllowAllConfigOption(strings))
                    else -> onSendMessage(selectedChat, "/" + command.name)
                }
            },
        )
        return
    }

    var selectedMachineId by remember(machines) { mutableStateOf(machines.firstOrNull()?.id.orEmpty()) }
    val selectedMachine = machines.firstOrNull { it.id == selectedMachineId } ?: machines.firstOrNull()
    var workspacePath by remember { mutableStateOf("") }
    var selectedAgentId by remember(selectedMachine) { mutableStateOf(selectedMachine?.agents?.firstOrNull()?.id.orEmpty()) }
    val selectedAgent = selectedMachine?.agents?.firstOrNull { it.id == selectedAgentId } ?: selectedMachine?.agents?.firstOrNull()
    var newChatMode by remember { mutableStateOf(NewChatMode.NewSession) }
    var existingSessions by remember { mutableStateOf<List<AgentSessionInfo>?>(null) }
    var existingSessionsLoading by remember { mutableStateOf(false) }
    var existingSessionsError by remember { mutableStateOf<String?>(null) }
    var selectedExistingSessionId by remember { mutableStateOf<String?>(null) }

    var newChatExpanded by remember { mutableStateOf(chats.isEmpty()) }
    LaunchedEffect(chats.isEmpty()) {
        if (chats.isEmpty()) {
            newChatExpanded = true
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(strings.newChat, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (newChatExpanded) strings.chooseMachineWorkspaceAgent else strings.tapCreateAgentSession,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        NewChatTogglePill(
                            expanded = newChatExpanded,
                            onClick = { newChatExpanded = !newChatExpanded },
                        )
                    }
                    if (newChatExpanded) {
                        Spacer(Modifier.height(10.dp))
                        NewChatModeSelector(
                            selectedMode = newChatMode,
                            onSelect = {
                                newChatMode = it
                                existingSessionsError = null
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Machine", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            machines.forEach { machine ->
                                SelectableOptionCard(
                                    selected = selectedMachine?.id == machine.id,
                                    title = machine.displayName,
                                    subtitle = "${machine.connectionState.name} · ${machine.endpoint}",
                                    onClick = {
                                        selectedMachineId = machine.id
                                        selectedAgentId = machine.agents.firstOrNull()?.id.orEmpty()
                                    },
                                )
                            }
                            if (machines.isEmpty()) {
                                Text(strings.pairMachineFirst, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Agent", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            selectedMachine?.agents.orEmpty().forEach { agent ->
                                SelectableOptionCard(
                                    selected = selectedAgent?.id == agent.id,
                                    title = agent.displayName,
                                    subtitle = agent.status,
                                    onClick = { selectedAgentId = agent.id },
                                )
                            }
                            if (selectedMachine?.agents.orEmpty().isEmpty()) {
                                Text(strings.noAgentDiscovered, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        if (newChatMode == NewChatMode.NewSession) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = workspacePath,
                                onValueChange = { workspacePath = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(strings.remoteWorkspacePath) },
                                placeholder = { Text(strings.remoteWorkspacePlaceholder) },
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                enabled = selectedMachine != null && selectedAgent != null,
                                onClick = { onCreateChat(selectedMachine!!, workspacePath, selectedAgent!!) },
                            ) {
                                Text(strings.createChat)
                            }
                        } else {
                            Spacer(Modifier.height(10.dp))
                            Button(
                                enabled = selectedMachine != null && selectedAgent != null && !existingSessionsLoading,
                                onClick = {
                                    existingSessionsLoading = true
                                    existingSessionsError = null
                                    existingSessions = null
                                    selectedExistingSessionId = null
                                    onLoadExistingSessions(selectedMachine!!, selectedAgent!!) { result ->
                                        existingSessionsLoading = false
                                        result
                                            .onSuccess {
                                                existingSessions = it
                                                selectedExistingSessionId = it.firstOrNull()?.sessionId
                                            }
                                            .onFailure {
                                                existingSessions = emptyList()
                                                existingSessionsError = it.message
                                            }
                                    }
                                },
                            ) {
                                Text(if (existingSessionsLoading) strings.loadingSessions else strings.loadSessions)
                            }
                            existingSessionsError?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(strings.couldNotLoadSessions(it), color = MaterialTheme.colorScheme.error)
                            }
                            existingSessions?.let { sessions ->
                                Spacer(Modifier.height(8.dp))
                                if (sessions.isEmpty()) {
                                    EmptyStateCard(strings.noResumableSessions, strings.noResumableSessionsForAgent)
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        sessions.take(12).forEach { session ->
                                            SelectableOptionCard(
                                                selected = selectedExistingSessionId == session.sessionId,
                                                title = session.title ?: session.sessionId,
                                                subtitle = listOfNotNull(session.cwd, session.updatedAt).joinToString(" · "),
                                                onClick = { selectedExistingSessionId = session.sessionId },
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    val selectedSession = sessions.firstOrNull { it.sessionId == selectedExistingSessionId }
                                    Button(
                                        enabled = selectedMachine != null && selectedAgent != null && selectedSession != null,
                                        onClick = { onOpenExistingSession(selectedMachine!!, selectedAgent!!, selectedSession!!) },
                                    ) {
                                        Text(strings.openSession)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionHeader(
                title = strings.chats,
                subtitle = strings.conversations(chats.size),
            )
        }
        if (chats.isEmpty()) {
            item { EmptyStateCard(strings.noChatsYet, strings.createChatAfterPairing) }
        } else {
            items(chats, key = { it.id }) { chat ->
                val machineState = machines.firstOrNull { it.id == chat.machineId }?.connectionState ?: ConnectionState.Unknown
                SwipeToDeleteItem(onDelete = { onDeleteChat(chat) }) {
                    ElevatedCard(onClick = { onOpenChat(chat) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ChatStatusDot(isBusy = chat.id in busyChatIds, connectionState = machineState)
                                Text(
                                    chat.title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (chat.id in unreadChatIds) {
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFDC2626)),
                                    )
                                }
                            }
                            Text("${chat.machineName} · ${chat.workspacePath}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(strings.messages(chat.messages.size), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatDetailScreen(
    padding: PaddingValues,
    chat: Chat,
    isBusy: Boolean,
    connectionState: ConnectionState,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onRemoveQueuedPrompt: (String) -> Unit,
    onCommand: (AvailableCommand) -> Unit,
) {
    val strings = LocalAppStrings.current
    var message by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    BackHandler(onBack = onBack)
    val commands = remember(chat.messages) {
        val advertisedCommands = chat.availableCommands()
        buildList {
            add(BUILT_IN_MODEL_COMMAND)
            add(BUILT_IN_RESUME_COMMAND)
            add(BUILT_IN_ALLOW_ALL_COMMAND)
            val builtIns = setOf(BUILT_IN_MODEL_COMMAND.name, BUILT_IN_RESUME_COMMAND.name, BUILT_IN_ALLOW_ALL_COMMAND.name)
            addAll(advertisedCommands.filterNot { it.name in builtIns }.sortedBy { COMMON_COMMAND_ORDER.indexOf(it.name).let { index -> if (index < 0) Int.MAX_VALUE else index } })
        }
    }
    LaunchedEffect(chat.id, chat.messages.size) {
        if (chat.messages.isNotEmpty()) {
            listState.scrollToItem(chat.messages.lastIndex)
        }
    }
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    val chatPadding = if (isImeVisible) {
        PaddingValues(top = padding.calculateTopPadding())
    } else {
        padding
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(chatPadding),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onBack) {
                    Text(strings.back)
                }
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChatStatusDot(isBusy = isBusy, connectionState = connectionState)
                        Text(chat.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text("${chat.machineName} · ${chat.workspacePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(chat.agentName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(chat.messages) { item ->
                    ChatTimelineItem(item)
                }
            }

            Surface(tonalElevation = 4.dp) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    val visibleQueuedPrompts = chat.queuedPrompts.filterNot { it.removing }
                    if (visibleQueuedPrompts.isNotEmpty()) {
                        Text(
                            strings.queuedPrompts(visibleQueuedPrompts.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        visibleQueuedPrompts.forEach { queued ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    queued.text,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Surface(
                                    modifier = Modifier.clickable { onRemoveQueuedPrompt(queued.operationId) },
                                    shape = RoundedCornerShape(999.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    color = Color.Transparent,
                                ) {
                                    Text(
                                        strings.removeQueuedPrompt,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                    if (commands.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            commands.forEach { command ->
                                val sessionOperation = command.name in setOf(
                                    BUILT_IN_MODEL_COMMAND.name,
                                    BUILT_IN_RESUME_COMMAND.name,
                                    BUILT_IN_ALLOW_ALL_COMMAND.name,
                                )
                                CommandPill(
                                    command = command,
                                    enabled = !isBusy || !sessionOperation,
                                    onClick = { onCommand(command) },
                                )
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompactPromptField(
                            value = message,
                            onValueChange = { message = it },
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = message.isNotBlank(),
                            onClick = {
                                onSendMessage(message)
                                message = ""
                            },
                            modifier = Modifier.defaultMinSize(minWidth = 68.dp, minHeight = 42.dp),
                        ) {
                            Text(if (isBusy) strings.appendPrompt else strings.send)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandPill(command: AvailableCommand, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.76f else 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            command.name,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatStatusDot(isBusy: Boolean, connectionState: ConnectionState) {
    val strings = LocalAppStrings.current
    val (color, label) = when {
        isBusy -> Color(0xFFDC2626) to strings.chatStatusBusy
        connectionState == ConnectionState.Online -> Color(0xFF16A34A) to strings.chatStatusReady
        else -> Color(0xFF9CA3AF) to strings.chatStatusOffline
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.58f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, RoundedCornerShape(999.dp)),
            )
            Text(
                text = label,
                fontSize = 9.sp,
                lineHeight = 9.sp,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CompactPromptField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalAppStrings.current
    Surface(
        modifier = modifier.defaultMinSize(minHeight = 42.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isBlank()) {
                Text(
                    strings.prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                singleLine = true,
            )
        }
    }
}

@Composable
private fun ModelDialog(state: ModelDialogState, onDismiss: () -> Unit, onSelect: (ConfigOptionValue) -> Unit) {
    val strings = LocalAppStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.option.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.option.options.isEmpty()) {
                    Text(strings.configOptionsNotLoaded, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    state.option.options.forEach { option ->
                        val selected = option.value == state.option.currentValue
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
                            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(option.name, fontWeight = FontWeight.SemiBold)
                                    if (selected) Text(strings.current, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                option.description?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(strings.close)
            }
        },
    )
}

@Composable
private fun ResumeDialog(state: ResumeDialogState, onDismiss: () -> Unit, onSelect: (AgentSessionInfo) -> Unit) {
    val strings = LocalAppStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.resumeSession) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.error?.let {
                    Text(strings.couldNotLoadSessions(it), color = MaterialTheme.colorScheme.error)
                }
                when (val sessions = state.sessions) {
                    null -> Text(strings.loadingSessionsFrom(state.chat.agentName))
                    else -> {
                        if (sessions.isEmpty() && state.error == null) {
                            Text(strings.noResumableSessionsWorkspace)
                        }
                        sessions.take(8).forEach { session ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(session) },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(session.title ?: session.sessionId, fontWeight = FontWeight.SemiBold)
                                    session.cwd?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    session.updatedAt?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(strings.close)
            }
        },
    )
}

@Composable
private fun ChatTimelineItem(item: ChatMessage) {
    if (item.kind == ChatMessageKind.CommandUpdate || item.kind == ChatMessageKind.ConfigUpdate) {
        return
    }
    if (item.kind == ChatMessageKind.Activity) {
        AgentActivityItem(item)
        return
    }
    if (item.kind == ChatMessageKind.Plan) {
        AgentPlanItem(item)
        return
    }

    val isUser = item.role == MessageRole.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        val color = when (item.role) {
            MessageRole.User -> MaterialTheme.colorScheme.primary
            MessageRole.Agent -> MaterialTheme.colorScheme.secondaryContainer
            MessageRole.System -> MaterialTheme.colorScheme.surface
        }
        val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            color = color,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (!isUser) {
                    Text(item.role.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = textColor.copy(alpha = 0.75f))
                }
                if (isUser) {
                    Text(item.text, color = textColor)
                } else {
                    MarkdownMessageText(item.text, textColor)
                }
            }
        }
    }
}

@Composable
private fun MarkdownMessageText(text: String, color: Color) {
    val lines = text.lines()
    var codeFenceLength: Int? = null
    val codeLines = mutableListOf<String>()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var lineIndex = 0
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val fenceLength = markdownCodeFenceDelimiterLength(line, codeFenceLength)
            if (fenceLength != null) {
                if (codeFenceLength != null) {
                    CodeBlock(codeLines.joinToString("\n"))
                    codeLines.clear()
                    codeFenceLength = null
                } else {
                    codeFenceLength = fenceLength
                }
                lineIndex++
                continue
            }

            if (codeFenceLength != null) {
                codeLines.add(line)
                lineIndex++
                continue
            }

            val table = parseMarkdownTable(lines, lineIndex)
            if (table != null) {
                MarkdownTableBlock(table.table, color)
                lineIndex += table.consumedLineCount
                continue
            }

            val trimmed = line.trim()
            when {
                trimmed.isBlank() -> Spacer(Modifier.height(4.dp))
                trimmed.startsWith("### ") -> Text(parseInlineMarkdown(trimmed.removePrefix("### ")), color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                trimmed.startsWith("## ") -> Text(parseInlineMarkdown(trimmed.removePrefix("## ")), color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                trimmed.startsWith("# ") -> Text(parseInlineMarkdown(trimmed.removePrefix("# ")), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Text(parseInlineMarkdown("• " + trimmed.drop(2)), color = color)
                trimmed.startsWith("> ") -> QuoteBlock(trimmed.removePrefix("> "), color)
                else -> Text(parseInlineMarkdown(line), color = color)
            }
            lineIndex++
        }
        if (codeFenceLength != null && codeLines.isNotEmpty()) {
            CodeBlock(codeLines.joinToString("\n"))
        }
    }
}

@Composable
private fun MarkdownTableBlock(table: MarkdownTable, color: Color) {
    val columnWidths = table.headers.indices.map { columnIndex ->
        val longestCell = (listOf(table.headers[columnIndex]) + table.rows.map { it[columnIndex] })
            .maxOf { it.length }
        (longestCell * 7 + 28).coerceIn(96, 220).dp
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = Color.Transparent,
    ) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            MarkdownTableRow(table.headers, table.alignments, columnWidths, color, header = true)
            table.rows.forEach { row ->
                MarkdownTableRow(row, table.alignments, columnWidths, color, header = false)
            }
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    alignments: List<MarkdownTableAlignment>,
    columnWidths: List<androidx.compose.ui.unit.Dp>,
    color: Color,
    header: Boolean,
) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        cells.forEachIndexed { index, cell ->
            val textAlign = when (alignments[index]) {
                MarkdownTableAlignment.Start -> TextAlign.Start
                MarkdownTableAlignment.Center -> TextAlign.Center
                MarkdownTableAlignment.End -> TextAlign.End
            }
            Surface(
                modifier = Modifier
                    .width(columnWidths[index])
                    .fillMaxHeight()
                    .defaultMinSize(minHeight = 40.dp),
                color = if (header) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) else Color.Transparent,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(
                    text = parseInlineMarkdown(cell),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    color = color,
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = textAlign,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = Color.Black.copy(alpha = 0.10f)) {
        Text(
            text = code,
            modifier = Modifier.padding(10.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun QuoteBlock(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(10.dp), color = Color.Black.copy(alpha = 0.06f)) {
        Text(
            text = parseInlineMarkdown(text),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            color = color,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun parseInlineMarkdown(input: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var index = 0
    while (index < input.length) {
        when {
            input.startsWith("**", index) -> {
                val end = input.indexOf("**", startIndex = index + 2)
                if (end > index) {
                    builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    builder.append(input.substring(index + 2, end))
                    builder.pop()
                    index = end + 2
                } else {
                    builder.append(input[index])
                    index++
                }
            }
            input[index] == '`' -> {
                val delimiterLength = countInlineBackticks(input, index)
                val contentStart = index + delimiterLength
                val end = findClosingInlineBackticks(input, contentStart, delimiterLength)
                if (end >= contentStart) {
                    builder.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.Black.copy(alpha = 0.10f)))
                    builder.append(input.substring(contentStart, end))
                    builder.pop()
                    index = end + delimiterLength
                } else {
                    builder.append(input.substring(index, contentStart))
                    index = contentStart
                }
            }
            input[index] == '*' -> {
                val end = input.indexOf('*', startIndex = index + 1)
                if (end > index) {
                    builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    builder.append(input.substring(index + 1, end))
                    builder.pop()
                    index = end + 1
                } else {
                    builder.append(input[index])
                    index++
                }
            }
            input[index] == '[' -> {
                val close = input.indexOf(']', startIndex = index + 1)
                val openParen = if (close > index) input.indexOf('(', startIndex = close + 1) else -1
                val closeParen = if (openParen == close + 1) input.indexOf(')', startIndex = openParen + 1) else -1
                if (close > index && openParen == close + 1 && closeParen > openParen) {
                    builder.pushStyle(SpanStyle(color = Color(0xFF2563EB), textDecoration = TextDecoration.Underline))
                    builder.append(input.substring(index + 1, close))
                    builder.pop()
                    index = closeParen + 1
                } else {
                    builder.append(input[index])
                    index++
                }
            }
            else -> {
                builder.append(input[index])
                index++
            }
        }
    }
    return builder.toAnnotatedString()
}

private fun countInlineBackticks(value: String, startIndex: Int): Int {
    var index = startIndex
    while (index < value.length && value[index] == '`') index++
    return index - startIndex
}

private fun findClosingInlineBackticks(value: String, startIndex: Int, delimiterLength: Int): Int {
    var index = startIndex
    while (index < value.length) {
        if (value[index] != '`') {
            index++
            continue
        }
        val runLength = countInlineBackticks(value, index)
        if (runLength == delimiterLength) return index
        index += runLength
    }
    return -1
}

@Composable
private fun AgentActivityItem(item: ChatMessage) {
    val strings = LocalAppStrings.current
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.70f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(item.title ?: strings.agentActivity, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(item.text, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (expanded) strings.hide else strings.details, style = MaterialTheme.typography.labelMedium)
            }
            if (expanded && !item.details.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)) {
                    Text(
                        text = item.details,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentPlanItem(item: ChatMessage) {
    val strings = LocalAppStrings.current
    val plan = remember(item.details) { parseAgentPlan(item.details) }
    if (plan == null) {
        AgentActivityItem(item.copy(kind = ChatMessageKind.Activity, title = strings.plan))
        return
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier.widthIn(min = 210.dp, max = 300.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.plan,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                ) {
                    Text(
                        text = "${plan.completedCount}/${plan.entries.size}",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            plan.entries.forEach { entry ->
                val statusColor = when (entry.status) {
                    AgentPlanEntryStatus.Completed -> MaterialTheme.colorScheme.primary
                    AgentPlanEntryStatus.InProgress -> MaterialTheme.colorScheme.tertiary
                    AgentPlanEntryStatus.Pending -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    AgentPlanEntryStatus.Unknown -> MaterialTheme.colorScheme.outline
                }
                val statusMarker = when (entry.status) {
                    AgentPlanEntryStatus.Completed -> "✓"
                    AgentPlanEntryStatus.InProgress -> "›"
                    AgentPlanEntryStatus.Pending -> "○"
                    AgentPlanEntryStatus.Unknown -> "?"
                }
                val priorityColor = when (entry.priority) {
                    AgentPlanEntryPriority.High -> MaterialTheme.colorScheme.error
                    AgentPlanEntryPriority.Medium -> MaterialTheme.colorScheme.tertiary
                    AgentPlanEntryPriority.Low -> MaterialTheme.colorScheme.outline
                    AgentPlanEntryPriority.Unknown -> Color.Transparent
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = statusMarker,
                        modifier = Modifier.width(12.dp),
                        color = statusColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = parseInlineMarkdown(entry.content),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (entry.status == AgentPlanEntryStatus.Completed) {
                            TextDecoration.LineThrough
                        } else {
                            TextDecoration.None
                        },
                    )
                    if (priorityColor != Color.Transparent) {
                        Box(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(6.dp)
                                .background(priorityColor, CircleShape),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalsScreen(
    padding: PaddingValues,
    approvals: List<Approval>,
    onDecision: (Approval, ApprovalStatus) -> Unit,
    onDeleteApproval: (Approval) -> Unit,
) {
    val strings = LocalAppStrings.current
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PageHero(
                title = strings.approvals,
                subtitle = strings.approvalsSubtitle,
                metric = strings.pending(approvals.count { it.status == ApprovalStatus.Pending }),
            )
        }
        if (approvals.isEmpty()) {
            item { EmptyStateCard(strings.noApprovalRequests, strings.approvalRequestsAppear) }
        } else {
            items(approvals, key = { it.id }) { approval ->
                SwipeToDeleteItem(onDelete = { onDeleteApproval(approval) }) {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(approval.summary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(approval.status.name)
                            }
                            Text("${approval.machineName} · ${approval.workspacePath}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(enabled = approval.status == ApprovalStatus.Pending, onClick = { onDecision(approval, ApprovalStatus.Approved) }) { Text(strings.approve) }
                                OutlinedButton(enabled = approval.status == ApprovalStatus.Pending, onClick = { onDecision(approval, ApprovalStatus.Denied) }) { Text(strings.deny) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MachinesScreen(
    padding: PaddingValues,
    machines: List<Machine>,
    statusMessage: String?,
    onPairLink: (String) -> Unit,
    onScanQr: () -> Unit,
    onRefreshMachine: (Machine) -> Unit,
    onDeleteMachine: (Machine) -> Unit,
) {
    val strings = LocalAppStrings.current
    var pairingLink by remember { mutableStateOf("") }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(18.dp)) {
                    Text(strings.addMachine, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(strings.addMachineSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onScanQr) { Text(strings.scanQr) }
                        OutlinedButton(enabled = pairingLink.isNotBlank(), onClick = { onPairLink(pairingLink) }) { Text(strings.pairLink) }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = pairingLink, onValueChange = { pairingLink = it }, modifier = Modifier.fillMaxWidth(), label = { Text(strings.pairingLinkFallback) }, minLines = 2)
                    statusMessage?.let {
                        Spacer(Modifier.height(10.dp))
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        }
        item { SectionHeader(strings.machines, strings.machinesPaired(machines.size)) }
        if (machines.isEmpty()) {
            item { EmptyStateCard(strings.noMachinesPaired, strings.startBridgePairMachine) }
        } else {
            items(machines, key = { it.id }) { machine ->
                SwipeToDeleteItem(onDelete = { onDeleteMachine(machine) }) {
                    MachineCard(machine = machine, onRefresh = { onRefreshMachine(machine) })
                }
            }
        }
    }
}

@Composable
private fun SwipeToDeleteItem(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val revealWidth = 104.dp
    val revealWidthPx = with(LocalDensity.current) { revealWidth.toPx() }
    val scope = rememberCoroutineScope()
    var offsetPx by remember { mutableFloatStateOf(0f) }
    var gestureDeltaPx by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val shape = RoundedCornerShape(18.dp)

    fun settle(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            Animatable(offsetPx).animateTo(target) {
                offsetPx = value
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Button(
                onClick = onDelete,
                modifier = Modifier
                    .width(revealWidth)
                    .padding(horizontal = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(LocalAppStrings.current.delete, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .pointerInput(revealWidthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            settleJob?.cancel()
                            gestureDeltaPx = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            gestureDeltaPx += dragAmount
                            offsetPx = (offsetPx + dragAmount).coerceIn(-revealWidthPx, 0f)
                        },
                        onDragEnd = {
                            settle(
                                if (gestureDeltaPx > 0f) {
                                    0f
                                } else if (offsetPx <= -revealWidthPx * 0.4f) {
                                    -revealWidthPx
                                } else {
                                    0f
                                },
                            )
                        },
                        onDragCancel = {
                            settle(0f)
                        },
                    )
                },
        ) {
            content()
        }
    }
}

@Composable
private fun NewChatTogglePill(expanded: Boolean, onClick: () -> Unit) {
    val strokeColor = MaterialTheme.colorScheme.primary
    val fillColor = if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.18f)
    Surface(
        modifier = Modifier
            .size(width = 58.dp, height = 32.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = fillColor,
        border = BorderStroke(2.dp, strokeColor),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth(if (expanded) 0.50f else 0.62f)
                    .height(2.dp)
                    .background(strokeColor, RoundedCornerShape(999.dp)),
            )
            if (!expanded) {
                Box(
                    Modifier
                        .size(width = 2.dp, height = 13.dp)
                        .background(strokeColor, RoundedCornerShape(999.dp)),
                )
            }
        }
    }
}

@Composable
private fun NewChatModeSelector(selectedMode: NewChatMode, onSelect: (NewChatMode) -> Unit) {
    val strings = LocalAppStrings.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NewChatMode.entries.forEach { mode ->
            val selected = selectedMode == mode
            val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(mode) },
                shape = RoundedCornerShape(16.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
                border = BorderStroke(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
                tonalElevation = if (selected) 4.dp else 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(strings.newChatModeLabel(mode), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = contentColor)
                    if (selected) {
                        Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.primary) {
                            Text(
                                "✓",
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectableOptionCard(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    val strings = LocalAppStrings.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        tonalElevation = if (selected) 3.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.primary) {
                    Text(strings.selected, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PageHero(title: String, subtitle: String, metric: String) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)) {
                Text(metric, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun EmptyStateCard(title: String, subtitle: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MachineCard(machine: Machine, onRefresh: () -> Unit) {
    val strings = LocalAppStrings.current
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(machine.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StateChip(machine.connectionState)
            }
            Text(machine.endpoint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            machine.bridgeVersion?.let { Text("Bridge: $it") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onRefresh) { Text(strings.testConnection) }
            if (machine.agents.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(strings.agents, style = MaterialTheme.typography.titleSmall)
                machine.agents.forEach { agent -> Text("${agent.displayName}: ${agent.status}") }
            }
        }
    }
}

@Composable
private fun StateChip(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.Online -> MaterialTheme.colorScheme.primaryContainer
        ConnectionState.Offline -> MaterialTheme.colorScheme.errorContainer
        ConnectionState.Unknown -> MaterialTheme.colorScheme.surfaceVariant
    }
    FilterChip(selected = false, onClick = {}, label = { Text(state.name) }, enabled = false, colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(disabledContainerColor = color))
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun QrScannerScreen(onLinkScanned: (String) -> Unit, onClose: () -> Unit) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(strings.scanPairingQr, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(strings.pointCameraAtBridgeQr, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onClose) { Text(strings.close) }
            }
            Spacer(Modifier.height(16.dp))
            if (hasCameraPermission) {
                CameraScannerPreview(modifier = Modifier.fillMaxWidth().weight(1f), onQrFound = onLinkScanned)
            } else {
                Column(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(strings.cameraAccessNeeded)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text(strings.grantCameraPermission) }
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraScannerPreview(modifier: Modifier, onQrFound: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    val handled = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            scanner.close()
        }
    }

    Box(modifier = modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                val previewView = PreviewView(viewContext)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(viewContext)
                cameraProviderFuture.addListener(
                    {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
                            it.setAnalyzer(cameraExecutor) { imageProxy ->
                                if (handled.get()) {
                                    imageProxy.close()
                                } else {
                                    scanQrCode(imageProxy, scanner) { value ->
                                        if (handled.compareAndSet(false, true)) {
                                            ContextCompat.getMainExecutor(context).execute { onQrFound(value) }
                                        }
                                    }
                                }
                            }
                        }
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    },
                    ContextCompat.getMainExecutor(viewContext),
                )
                previewView
            },
        )
        Box(modifier = Modifier.align(Alignment.Center).size(220.dp).clip(RoundedCornerShape(28.dp)).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.03f)))))
    }
}

@ExperimentalGetImage
private fun scanQrCode(imageProxy: ImageProxy, scanner: com.google.mlkit.vision.barcode.BarcodeScanner, onQrFound: (String) -> Unit) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    scanner.process(InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees))
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull { it.rawValue?.startsWith("acpclient://pair") == true }?.rawValue?.let(onQrFound)
        }
        .addOnCompleteListener { imageProxy.close() }
}

@Composable
private fun PlaceholderScreen(padding: PaddingValues, title: String, body: String) {
    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body)
    }
}

private fun Chat.withMessage(role: MessageRole, text: String): Chat {
    return copy(messages = messages + ChatMessage(role, text, System.currentTimeMillis()))
}

private fun List<ChatMessage>.mergeMessage(message: ChatMessage): List<ChatMessage> {
    val streamId = message.activityId ?: return this + message
    val existingIndex = if (message.kind == ChatMessageKind.Message) {
        indexOfLast { it.activityId == streamId }
    } else {
        indexOfFirst { it.activityId == streamId }
    }
    if (existingIndex < 0) return this + message
    if (message.kind == ChatMessageKind.Message && existingIndex != lastIndex) return this + message
    return toMutableList().also { current ->
        val existing = current[existingIndex]
        current[existingIndex] = when {
            message.kind == ChatMessageKind.Activity && existing.kind == ChatMessageKind.Activity -> {
                if (existing.title == "Thought" && message.title == "Thought") {
                    existing.copy(details = listOfNotNull(existing.details, message.details).joinToString(""))
                } else {
                    message
                }
            }
            message.kind == ChatMessageKind.Message && existing.kind == ChatMessageKind.Message -> existing.copy(text = existing.text + message.text)
            else -> message
        }
    }
}

private fun Chat.withActivity(title: String, summary: String, details: String): Chat {
    return copy(
        messages = messages + ChatMessage(
            role = MessageRole.Agent,
            text = summary,
            timestampMillis = System.currentTimeMillis(),
            kind = ChatMessageKind.Activity,
            title = title,
            details = details,
            activityId = title,
        ),
    )
}

private fun Chat.availableCommands(): List<AvailableCommand> {
    val latest = messages.lastOrNull { it.kind == ChatMessageKind.CommandUpdate && !it.details.isNullOrBlank() } ?: return emptyList()
    val commandDetails = latest.details ?: return emptyList()
    val array = runCatching { JSONArray(commandDetails) }.getOrNull() ?: return emptyList()
    return List(array.length()) { index ->
        val item = array.getJSONObject(index)
        val input = item.optJSONObject("input")
        AvailableCommand(
            name = item.getString("name"),
            description = item.optString("description"),
            inputHint = input?.optString("hint")?.ifBlank { null },
        )
    }
}

private fun Chat.modelConfigOption(): ConfigOption? {
    return configOptions().firstOrNull { option ->
        option.id.normalizedKey() == "model" || option.category?.normalizedKey() == "model" || option.name.normalizedKey() == "model"
    }
}

private fun Chat.allowAllConfigOption(): ConfigOption? {
    return configOptions().firstOrNull { option ->
        option.id.normalizedKey() in ALLOW_ALL_KEYS ||
            option.category?.normalizedKey() in ALLOW_ALL_KEYS ||
            option.name.normalizedKey() in ALLOW_ALL_KEYS
    }
}

private fun Chat.configOptions(): List<ConfigOption> {
    val latest = messages.lastOrNull { it.kind == ChatMessageKind.ConfigUpdate && !it.details.isNullOrBlank() } ?: return emptyList()
    val configDetails = latest.details ?: return emptyList()
    val array = runCatching { JSONArray(configDetails) }.getOrNull() ?: return emptyList()
    val options = List(array.length()) { index -> array.getJSONObject(index) }
    return options.mapNotNull { option ->
        val id = option.getString("id")
        val name = option.optString("name").ifBlank { id }
        val category = option.optString("category").ifBlank { null }
        val type = option.optString("type")
        val key = id.normalizedKey()
        val values = when {
            type == "select" -> option.optJSONArray("options") ?: JSONArray()
            type == "boolean" || key in ALLOW_ALL_KEYS -> JSONArray()
                .put(JSONObject().put("value", "true").put("name", "On"))
                .put(JSONObject().put("value", "false").put("name", "Off"))
            else -> return@mapNotNull null
        }
        ConfigOption(
            id = id,
            name = name,
            category = category,
            type = type,
            currentValue = option.optString("currentValue").ifBlank { null },
            options = List(values.length()) { index ->
                val item = values.getJSONObject(index)
                ConfigOptionValue(
                    value = item.getString("value"),
                    name = item.optString("name").ifBlank { item.getString("value") },
                    description = item.optString("description").ifBlank { null },
                )
            }
        )
    }
}

private fun fallbackModelConfigOption(): ConfigOption {
    return ConfigOption(
        id = "model",
        name = "Model",
        category = "model",
        type = "select",
        currentValue = null,
        options = emptyList(),
    )
}

private fun fallbackAllowAllConfigOption(strings: AppStrings): ConfigOption {
    return ConfigOption(
        id = "allow_all",
        name = "Allow All",
        category = "approval",
        type = "boolean",
        currentValue = null,
        options = listOf(
            ConfigOptionValue("true", strings.on, null),
            ConfigOptionValue("false", strings.off, null),
        ),
    )
}

private val ALLOW_ALL_KEYS = setOf("allowall", "allowallpermissions", "autoapprove", "autoapproval")

private fun String.normalizedKey(): String = lowercase().filter { it.isLetterOrDigit() }

private val BUILT_IN_RESUME_COMMAND = AvailableCommand(
    name = "resume",
    description = "Load a previous ACP session for this workspace.",
)

private val BUILT_IN_MODEL_COMMAND = AvailableCommand(
    name = "model",
    description = "Select the model for this ACP session.",
)

private val BUILT_IN_ALLOW_ALL_COMMAND = AvailableCommand(
    name = "allow-all",
    description = "Toggle automatic permission approval for this session.",
)

private val COMMON_COMMAND_ORDER = listOf(
    "plan",
    "review",
    "init",
    "model",
    "allow-all",
    "usage",
    "context",
)

private data class ResumeDialogState(
    val chat: Chat,
    val sessions: List<AgentSessionInfo>?,
    val error: String?,
)

private data class ModelDialogState(
    val chat: Chat,
    val option: ConfigOption,
)

private data class UpdateUiState(
    val checking: Boolean = false,
    val update: AppUpdate? = null,
    val status: UpdateStatus = UpdateStatus.Idle,
    val errorMessage: String? = null,
)

private enum class UpdateStatus {
    Idle,
    Checking,
    NoReleases,
    UpdateAvailable,
    Latest,
    Failed,
}

private fun UpdateUiState.localizedMessage(strings: AppStrings): String? {
    return when (status) {
        UpdateStatus.Idle -> null
        UpdateStatus.Checking -> strings.checkingForUpdates
        UpdateStatus.NoReleases -> strings.noReleasesFound
        UpdateStatus.UpdateAvailable -> update?.let { strings.updateAvailable(it.version) }
        UpdateStatus.Latest -> strings.latestVersion
        UpdateStatus.Failed -> strings.updateCheckFailed(errorMessage)
    }
}

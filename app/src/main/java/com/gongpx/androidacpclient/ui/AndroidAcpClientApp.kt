package com.gongpx.androidacpclient.ui

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.gongpx.androidacpclient.data.bridge.BridgeClient
import com.gongpx.androidacpclient.data.model.Agent
import com.gongpx.androidacpclient.data.model.AgentSessionInfo
import com.gongpx.androidacpclient.data.model.Approval
import com.gongpx.androidacpclient.data.model.ApprovalStatus
import com.gongpx.androidacpclient.data.model.AvailableCommand
import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.model.ChatMessage
import com.gongpx.androidacpclient.data.model.ChatMessageKind
import com.gongpx.androidacpclient.data.model.ConfigOption
import com.gongpx.androidacpclient.data.model.ConfigOptionValue
import com.gongpx.androidacpclient.data.model.ConnectionState
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.model.MessageRole
import com.gongpx.androidacpclient.data.pairing.PairingLinkParser
import com.gongpx.androidacpclient.data.store.ChatStore
import com.gongpx.androidacpclient.data.store.MachineStore
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import org.json.JSONArray

private enum class AppTab(val label: String, val icon: String) {
    Chats("Chats", "✦"),
    Approvals("Approvals", "✓"),
    Machines("Machines", "⌁"),
    Settings("Settings", "⚙"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentLinkApp(incomingPairingLink: MutableState<String?>) {
    val context = LocalContext.current
    val machineStore = remember { MachineStore(context.applicationContext) }
    val chatStore = remember { ChatStore(context.applicationContext) }
    val bridgeClient = remember { BridgeClient() }
    val parser = remember { PairingLinkParser() }
    val machines = remember { mutableStateListOf<Machine>() }
    val chats = remember { mutableStateListOf<Chat>() }
    val approvals = remember { mutableStateListOf<Approval>() }
    var selectedTab by remember { mutableStateOf(AppTab.Machines) }
    var selectedChatId by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var scannerOpen by remember { mutableStateOf(false) }
    var resumeDialogState by remember { mutableStateOf<ResumeDialogState?>(null) }
    var modelDialogState by remember { mutableStateOf<ModelDialogState?>(null) }
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

    fun createChat(machine: Machine, workspacePath: String, agent: Agent) {
        val path = workspacePath.trim()
        if (path.isBlank()) {
            statusMessage = "Enter a remote workspace path before creating a chat."
            return
        }
        val workspaceName = path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { path }
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
                    text = "Chat created. Workspace is selected per chat, not at bridge startup.",
                    timestampMillis = System.currentTimeMillis(),
                ),
            ),
        )
        upsertChat(chat)
        selectedChatId = chat.id
        selectedTab = AppTab.Chats
    }

    fun addApproval(chat: Chat) {
        approvals.add(
            Approval(
                id = "approval_" + UUID.randomUUID(),
                chatId = chat.id,
                chatTitle = chat.title,
                machineId = chat.machineId,
                machineName = chat.machineName,
                workspacePath = chat.workspacePath,
                action = "run_command",
                summary = "Run test command in ${chat.workspacePath}",
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        selectedTab = AppTab.Approvals
    }

    fun updateApproval(approval: Approval, status: ApprovalStatus) {
        val index = approvals.indexOfFirst { it.id == approval.id }
        if (index >= 0) approvals[index] = approval.copy(status = status)
        val machine = machines.firstOrNull { it.id == approval.machineId } ?: return
        scope.launch {
            bridgeClient.sendApprovalDecision(machine, approval.id, if (status == ApprovalStatus.Approved) "approved" else "denied")
        }
    }

    fun showResumeDialog(chat: Chat) {
        val machine = machines.firstOrNull { it.id == chat.machineId }
        if (machine == null) {
            upsertChat(chat.withMessage(MessageRole.System, "Machine is not available."))
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
            upsertChat(chat.withMessage(MessageRole.System, "Machine is not available."))
            return
        }
        resumeDialogState = null
        val loading = chat.withActivity(
            title = "Resume session",
            summary = session.title ?: session.sessionId,
            details = "sessionId=${session.sessionId}\ncwd=${session.cwd.orEmpty()}\nupdatedAt=${session.updatedAt.orEmpty()}",
        )
        upsertChat(loading)
        scope.launch {
            bridgeClient.loadSession(machine, chat.id, chat.agentId, chat.workspacePath, session.sessionId)
                .onSuccess { events ->
                    upsertChat(loading.copy(messages = loading.messages + events))
                }
                .onFailure {
                    upsertChat(loading.withMessage(MessageRole.System, "Resume failed: ${it.message}"))
                }
        }
    }

    fun showModelDialog(chat: Chat, option: ConfigOption) {
        modelDialogState = ModelDialogState(chat = chat, option = option)
    }

    fun setModel(chat: Chat, option: ConfigOption, value: ConfigOptionValue) {
        val machine = machines.firstOrNull { it.id == chat.machineId }
        if (machine == null) {
            upsertChat(chat.withMessage(MessageRole.System, "Machine is not available."))
            return
        }
        modelDialogState = null
        val changing = chat.withActivity(
            title = "Set model",
            summary = value.name,
            details = "configId=${option.id}\nvalue=${value.value}\n${value.description.orEmpty()}",
        )
        upsertChat(changing)
        scope.launch {
            bridgeClient.setConfigOption(machine, chat.id, chat.agentId, chat.workspacePath, option.id, value.value)
                .onSuccess { events ->
                    upsertChat(changing.copy(messages = changing.messages + events))
                }
                .onFailure {
                    upsertChat(changing.withMessage(MessageRole.System, "Model change failed: ${it.message}"))
                }
        }
    }

    fun pairFromLink(link: String) {
        val payload = parser.parse(link).getOrElse {
            statusMessage = it.message ?: "Invalid pairing link."
            return
        }
        statusMessage = "Waiting for bridge approval on ${payload.machineName}..."
        scope.launch {
            bridgeClient.redeemPairing(payload)
                .onSuccess { machine ->
                    upsertMachine(machine)
                    bridgeClient.fetchMachineDetails(machine)
                        .onSuccess {
                            upsertMachine(it)
                            statusMessage = "${it.displayName} is online."
                        }
                        .onFailure { statusMessage = "Paired, but health check failed: ${it.message}" }
                }
                .onFailure { statusMessage = "Pairing failed: ${it.message}" }
        }
    }

    LaunchedEffect(Unit) {
        machines.clear()
        machines.addAll(machineStore.load())
        chats.clear()
        chats.addAll(chatStore.load())
    }

    LaunchedEffect(incomingPairingLink.value) {
        incomingPairingLink.value?.let { link ->
            selectedTab = AppTab.Machines
            pairFromLink(link)
            incomingPairingLink.value = null
        }
    }

    MaterialTheme {
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
                                        "Mobile control for remote coding agents",
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
                                    label = { Text(tab.label) },
                                    icon = { Text(tab.icon, fontSize = 24.sp, fontWeight = FontWeight.Bold) },
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
                            selectedChatId = selectedChatId,
                            onCreateChat = ::createChat,
                            onOpenChat = { selectedChatId = it.id },
                            onBackToList = { selectedChatId = null },
                            onResume = ::showResumeDialog,
                            onModel = ::showModelDialog,
                            onSendMessage = { chat, message ->
                                val machine = machines.firstOrNull { it.id == chat.machineId }
                                if (machine == null) {
                                    upsertChat(chat.withMessage(MessageRole.System, "Machine is not available."))
                                } else {
                                    val updated = chat.withMessage(MessageRole.User, message)
                                    upsertChat(updated)
                                    scope.launch {
                                        bridgeClient.sendChatPrompt(machine, chat.id, chat.agentId, chat.workspacePath, message)
                                            .onSuccess { events ->
                                                upsertChat(updated.copy(messages = updated.messages + events))
                                            }
                                            .onFailure {
                                                upsertChat(updated.withMessage(MessageRole.System, "Bridge WebSocket failed: ${it.message}"))
                                            }
                                    }
                                }
                            },
                            onRequestApproval = ::addApproval,
                        )
                        AppTab.Approvals -> ApprovalsScreen(padding, approvals, ::updateApproval)
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
                                            statusMessage = "${it.displayName} is online."
                                        }
                                        .onFailure {
                                            upsertMachine(machine.copy(connectionState = ConnectionState.Offline))
                                            statusMessage = "Connection failed: ${it.message}"
                                        }
                                }
                            },
                        )
                        AppTab.Settings -> PlaceholderScreen(padding, "Settings", "Settings will manage security and bridge defaults.")
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

@Composable
private fun ChatsScreen(
    padding: PaddingValues,
    machines: List<Machine>,
    chats: List<Chat>,
    selectedChatId: String?,
    onCreateChat: (Machine, String, Agent) -> Unit,
    onOpenChat: (Chat) -> Unit,
    onBackToList: () -> Unit,
    onResume: (Chat) -> Unit,
    onModel: (Chat, ConfigOption) -> Unit,
    onSendMessage: (Chat, String) -> Unit,
    onRequestApproval: (Chat) -> Unit,
) {
    val selectedChat = chats.firstOrNull { it.id == selectedChatId }
    if (selectedChat != null) {
        ChatDetailScreen(
            padding = padding,
            chat = selectedChat,
            onBack = onBackToList,
            onSendMessage = { onSendMessage(selectedChat, it) },
            onCommand = { command ->
                when (command.name) {
                    BUILT_IN_MODEL_COMMAND.name -> selectedChat.modelConfigOption()?.let { onModel(selectedChat, it) }
                    BUILT_IN_RESUME_COMMAND.name -> onResume(selectedChat)
                    BUILT_IN_ALLOW_ALL_COMMAND.name -> selectedChat.allowAllConfigOption()?.let { onModel(selectedChat, it) }
                    else -> onSendMessage(selectedChat, "/" + command.name)
                }
            },
            onRequestApproval = { onRequestApproval(selectedChat) },
        )
        return
    }

    var selectedMachineId by remember(machines) { mutableStateOf(machines.firstOrNull()?.id.orEmpty()) }
    val selectedMachine = machines.firstOrNull { it.id == selectedMachineId } ?: machines.firstOrNull()
    var workspacePath by remember { mutableStateOf("") }
    var selectedAgentId by remember(selectedMachine) { mutableStateOf(selectedMachine?.agents?.firstOrNull()?.id.orEmpty()) }
    val selectedAgent = selectedMachine?.agents?.firstOrNull { it.id == selectedAgentId } ?: selectedMachine?.agents?.firstOrNull()

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
                            Text("New Chat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (newChatExpanded) "Choose machine, workspace, and agent" else "Tap to create another agent session",
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
                                Text("Pair a machine first from the Machines tab.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = workspacePath,
                            onValueChange = { workspacePath = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Remote workspace path") },
                            placeholder = { Text("D:\\repos\\project-a or /home/me/project-a") },
                        )
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
                                Text("No agent discovered on this machine yet.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            enabled = selectedMachine != null && selectedAgent != null && workspacePath.isNotBlank(),
                            onClick = { onCreateChat(selectedMachine!!, workspacePath, selectedAgent!!) },
                        ) {
                            Text("Create Chat")
                        }
                    }
                }
            }
        }
        item {
            SectionHeader(
                title = "Chats",
                subtitle = "${chats.size} conversation${if (chats.size == 1) "" else "s"}",
            )
        }
        if (chats.isEmpty()) {
            item { EmptyStateCard("No chats yet", "Create a chat above after pairing a machine.") }
        } else {
            items(chats, key = { it.id }) { chat ->
                ElevatedCard(onClick = { onOpenChat(chat) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(chat.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${chat.machineName} · ${chat.workspacePath}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${chat.messages.size} messages", style = MaterialTheme.typography.labelMedium)
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
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onCommand: (AvailableCommand) -> Unit,
    onRequestApproval: () -> Unit,
) {
    var message by remember { mutableStateOf("") }
    val commands = remember(chat.messages) {
        buildList {
            if (chat.modelConfigOption() != null) add(BUILT_IN_MODEL_COMMAND)
            add(BUILT_IN_RESUME_COMMAND)
            if (chat.allowAllConfigOption() != null) add(BUILT_IN_ALLOW_ALL_COMMAND)
            val builtIns = setOf(BUILT_IN_MODEL_COMMAND.name, BUILT_IN_RESUME_COMMAND.name, BUILT_IN_ALLOW_ALL_COMMAND.name)
            addAll(chat.availableCommands().filterNot { it.name in builtIns }.sortedBy { COMMON_COMMAND_ORDER.indexOf(it.name).let { index -> if (index < 0) Int.MAX_VALUE else index } })
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
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
                    Text("Back")
                }
                Column {
                    Text(chat.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${chat.machineName} · ${chat.workspacePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(chat.agentName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        LazyColumn(
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
            Column(Modifier.padding(12.dp)) {
                if (commands.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        commands.forEach { command ->
                            FilterChip(
                                selected = false,
                                onClick = { onCommand(command) },
                                label = { Text(command.name) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Prompt") },
                    minLines = 1,
                    maxLines = 4,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = message.isNotBlank(),
                        onClick = {
                            onSendMessage(message)
                            message = ""
                        },
                    ) {
                        Text("Send")
                    }
                    OutlinedButton(onClick = onRequestApproval) {
                        Text("Test Approval")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelDialog(state: ModelDialogState, onDismiss: () -> Unit, onSelect: (ConfigOptionValue) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.option.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                if (selected) Text("Current", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            option.description?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun ResumeDialog(state: ResumeDialogState, onDismiss: () -> Unit, onSelect: (AgentSessionInfo) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resume session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.error?.let {
                    Text("Could not load sessions: $it", color = MaterialTheme.colorScheme.error)
                }
                when (val sessions = state.sessions) {
                    null -> Text("Loading sessions from ${state.chat.agentName}...")
                    else -> {
                        if (sessions.isEmpty() && state.error == null) {
                            Text("No resumable sessions found for this workspace.")
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
                Text("Close")
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
    var inCodeBlock = false
    val codeLines = mutableListOf<String>()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (line in lines) {
            if (line.trim().startsWith("```")) {
                if (inCodeBlock) {
                    CodeBlock(codeLines.joinToString("\n"))
                    codeLines.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                codeLines.add(line)
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
        }
        if (inCodeBlock && codeLines.isNotEmpty()) {
            CodeBlock(codeLines.joinToString("\n"))
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
                val end = input.indexOf('`', startIndex = index + 1)
                if (end > index) {
                    builder.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.Black.copy(alpha = 0.10f)))
                    builder.append(input.substring(index + 1, end))
                    builder.pop()
                    index = end + 1
                } else {
                    builder.append(input[index])
                    index++
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

@Composable
private fun AgentActivityItem(item: ChatMessage) {
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
                    Text(item.title ?: "Agent activity", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(item.text, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (expanded) "Hide" else "Details", style = MaterialTheme.typography.labelMedium)
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
private fun ApprovalsScreen(padding: PaddingValues, approvals: List<Approval>, onDecision: (Approval, ApprovalStatus) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PageHero(
                title = "Approvals",
                subtitle = "Review commands, file changes, and risky actions before they run.",
                metric = "${approvals.count { it.status == ApprovalStatus.Pending }} pending",
            )
        }
        if (approvals.isEmpty()) {
            item { EmptyStateCard("No approval requests", "Agent requests that need your decision will appear here.") }
        } else {
            items(approvals, key = { it.id }) { approval ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(approval.summary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(approval.status.name)
                        }
                        Text("${approval.machineName} · ${approval.workspacePath}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(enabled = approval.status == ApprovalStatus.Pending, onClick = { onDecision(approval, ApprovalStatus.Approved) }) { Text("Approve") }
                            OutlinedButton(enabled = approval.status == ApprovalStatus.Pending, onClick = { onDecision(approval, ApprovalStatus.Denied) }) { Text("Deny") }
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
) {
    var pairingLink by remember { mutableStateOf("") }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Add Machine", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Scan the bridge QR code, or paste the acpclient://pair link.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onScanQr) { Text("Scan QR") }
                        OutlinedButton(enabled = pairingLink.isNotBlank(), onClick = { onPairLink(pairingLink) }) { Text("Pair Link") }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = pairingLink, onValueChange = { pairingLink = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Pairing link fallback") }, minLines = 2)
                    statusMessage?.let {
                        Spacer(Modifier.height(10.dp))
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        }
        item { SectionHeader("Machines", "${machines.size} paired") }
        if (machines.isEmpty()) {
            item { EmptyStateCard("No machines paired", "Start the bridge, scan the QR code, then test the connection.") }
        } else {
            items(machines, key = { it.id }) { machine ->
                MachineCard(machine = machine, onRefresh = { onRefreshMachine(machine) })
            }
        }
    }
}

@Composable
private fun NewChatTogglePill(expanded: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (expanded) {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.primary
        },
        tonalElevation = 3.dp,
    ) {
        Text(
            text = if (expanded) "Hide" else "+ New",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = if (expanded) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SelectableOptionCard(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
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
                    Text("Selected", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
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
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(machine.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StateChip(machine.connectionState)
            }
            Text(machine.endpoint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            machine.bridgeVersion?.let { Text("Bridge: $it") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onRefresh) { Text("Test Connection") }
            if (machine.agents.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Agents", style = MaterialTheme.typography.titleSmall)
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
                    Text("Scan Pairing QR", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Point the camera at the bridge QR code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
            Spacer(Modifier.height(16.dp))
            if (hasCameraPermission) {
                CameraScannerPreview(modifier = Modifier.fillMaxWidth().weight(1f), onQrFound = onLinkScanned)
            } else {
                Column(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera access is needed to scan pairing QR codes.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Grant Camera Permission") }
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
        option.id == "model" || option.category == "model"
    }
}

private fun Chat.allowAllConfigOption(): ConfigOption? {
    return configOptions().firstOrNull { option ->
        option.id == "allow_all" || option.name.equals("Allow All", ignoreCase = true)
    }
}

private fun Chat.configOptions(): List<ConfigOption> {
    val latest = messages.lastOrNull { it.kind == ChatMessageKind.ConfigUpdate && !it.details.isNullOrBlank() } ?: return emptyList()
    val configDetails = latest.details ?: return emptyList()
    val array = runCatching { JSONArray(configDetails) }.getOrNull() ?: return emptyList()
    val options = List(array.length()) { index -> array.getJSONObject(index) }
    return options.mapNotNull { option ->
        if (option.optString("type") != "select") return@mapNotNull null
        val values = option.optJSONArray("options") ?: JSONArray()
        ConfigOption(
            id = option.getString("id"),
            name = option.optString("name").ifBlank { option.getString("id") },
            category = option.optString("category").ifBlank { null },
            type = option.optString("type"),
            currentValue = option.optString("currentValue").ifBlank { null },
            options = List(values.length()) { index ->
                val item = values.getJSONObject(index)
                ConfigOptionValue(
                    value = item.getString("value"),
                    name = item.optString("name").ifBlank { item.getString("value") },
                    description = item.optString("description").ifBlank { null },
                )
            )
        )
    }
}

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

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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.gongpx.androidacpclient.data.bridge.BridgeClient
import com.gongpx.androidacpclient.data.model.Agent
import com.gongpx.androidacpclient.data.model.Approval
import com.gongpx.androidacpclient.data.model.ApprovalStatus
import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.model.ChatMessage
import com.gongpx.androidacpclient.data.model.ChatMessageKind
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

private enum class AppTab(val label: String) {
    Chats("Chats"),
    Approvals("Approvals"),
    Machines("Machines"),
    Settings("Settings"),
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
                    topBar = { TopAppBar(title = { Text("AgentLink", fontWeight = FontWeight.SemiBold) }) },
                    bottomBar = {
                        NavigationBar {
                            AppTab.entries.forEach { tab ->
                                NavigationBarItem(selected = selectedTab == tab, onClick = { selectedTab = tab }, label = { Text(tab.label) }, icon = {})
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
                            onSendMessage = { chat, message ->
                                val machine = machines.firstOrNull { it.id == chat.machineId }
                                if (machine == null) {
                                    upsertChat(chat.withMessage(MessageRole.System, "Machine is not available."))
                                } else {
                                    val updated = chat.withMessage(MessageRole.User, message)
                                    val withActivity = updated.withActivity(
                                        title = "Sending prompt",
                                        summary = "Calling bridge WebSocket",
                                        details = "type=chat.prompt\nchatId=${chat.id}\nworkspace=${chat.workspacePath}",
                                    )
                                    upsertChat(withActivity)
                                    scope.launch {
                                        bridgeClient.sendChatPrompt(machine, chat.id, message)
                                            .onSuccess { response ->
                                                upsertChat(
                                                    withActivity
                                                        .withActivity(
                                                            title = "Bridge response",
                                                            summary = "WebSocket response received",
                                                            details = response,
                                                        )
                                                        .withMessage(MessageRole.Agent, "Bridge acknowledged the prompt. ACP agent execution will attach here next."),
                                                )
                                            }
                                            .onFailure {
                                                upsertChat(withActivity.withMessage(MessageRole.System, "Bridge WebSocket failed: ${it.message}"))
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
    onSendMessage: (Chat, String) -> Unit,
    onRequestApproval: (Chat) -> Unit,
) {
    val selectedChat = chats.firstOrNull { it.id == selectedChatId }
    if (selectedChat != null) {
        ChatDetailScreen(padding, selectedChat, onBackToList, { onSendMessage(selectedChat, it) }, { onRequestApproval(selectedChat) })
        return
    }

    var selectedMachineId by remember(machines) { mutableStateOf(machines.firstOrNull()?.id.orEmpty()) }
    val selectedMachine = machines.firstOrNull { it.id == selectedMachineId } ?: machines.firstOrNull()
    var workspacePath by remember { mutableStateOf("") }
    var selectedAgentId by remember(selectedMachine) { mutableStateOf(selectedMachine?.agents?.firstOrNull()?.id.orEmpty()) }
    val selectedAgent = selectedMachine?.agents?.firstOrNull { it.id == selectedAgentId } ?: selectedMachine?.agents?.firstOrNull()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(18.dp)) {
                    Text("New Chat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Workspace is chosen here, not when the bridge starts.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(12.dp))
                    Text("Machine", style = MaterialTheme.typography.titleSmall)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        machines.forEach { machine ->
                            FilterChip(selected = selectedMachine?.id == machine.id, onClick = { selectedMachineId = machine.id }, label = { Text(machine.displayName) })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = workspacePath,
                        onValueChange = { workspacePath = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Remote workspace path") },
                        placeholder = { Text("D:\\repos\\project-a or /home/me/project-a") },
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Agent", style = MaterialTheme.typography.titleSmall)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        selectedMachine?.agents.orEmpty().forEach { agent ->
                            FilterChip(selected = selectedAgent?.id == agent.id, onClick = { selectedAgentId = agent.id }, label = { Text("${agent.displayName} (${agent.status})") })
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(enabled = selectedMachine != null && selectedAgent != null && workspacePath.isNotBlank(), onClick = { onCreateChat(selectedMachine!!, workspacePath, selectedAgent!!) }) {
                        Text("Create Chat")
                    }
                }
            }
        }
        item { Text("Chats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (chats.isEmpty()) {
            item { Text("No chats yet.") }
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
private fun ChatDetailScreen(padding: PaddingValues, chat: Chat, onBack: () -> Unit, onSendMessage: (String) -> Unit, onRequestApproval: () -> Unit) {
    var message by remember { mutableStateOf("") }
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
private fun ChatTimelineItem(item: ChatMessage) {
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
                Text(item.text, color = textColor)
            }
        }
    }
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
        item { Text("Approvals", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        if (approvals.isEmpty()) {
            item { Text("No approval requests yet.") }
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
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Machines", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${machines.size} paired", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (machines.isEmpty()) {
            item { Text("No machines paired yet.") }
        } else {
            items(machines, key = { it.id }) { machine ->
                MachineCard(machine = machine, onRefresh = { onRefreshMachine(machine) })
            }
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
        ),
    )
}

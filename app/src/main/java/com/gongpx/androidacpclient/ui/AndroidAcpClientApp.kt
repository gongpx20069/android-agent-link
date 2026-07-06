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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.gongpx.androidacpclient.data.model.ConnectionState
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.pairing.PairingLinkParser
import com.gongpx.androidacpclient.data.store.MachineStore
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    val store = remember { MachineStore(context.applicationContext) }
    val bridgeClient = remember { BridgeClient() }
    val parser = remember { PairingLinkParser() }
    val machines = remember { mutableStateListOf<Machine>() }
    var selectedTab by remember { mutableStateOf(AppTab.Machines) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var scannerOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun upsertMachine(machine: Machine) {
        val index = machines.indexOfFirst { it.id == machine.id }
        if (index >= 0) {
            machines[index] = machine
        } else {
            machines.add(machine)
        }
        store.upsert(machine)
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
                    statusMessage = "Paired ${machine.displayName}. Testing connection..."
                    bridgeClient.fetchMachineDetails(machine)
                        .onSuccess {
                            upsertMachine(it)
                            statusMessage = "${it.displayName} is online."
                        }
                        .onFailure {
                            statusMessage = "Paired, but health check failed: ${it.message}"
                        }
                }
                .onFailure {
                    statusMessage = "Pairing failed: ${it.message}"
                }
        }
    }

    LaunchedEffect(Unit) {
        machines.clear()
        machines.addAll(store.load())
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
                                    Text("Remote agent control", style = MaterialTheme.typography.labelMedium)
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
                                    icon = {},
                                )
                            }
                        }
                    },
                ) { padding ->
                    when (selectedTab) {
                        AppTab.Chats -> PlaceholderScreen(padding, "Chats", "Chat creation starts after a machine and workspace are paired.")
                        AppTab.Approvals -> PlaceholderScreen(padding, "Approvals", "Pending agent approvals will appear here.")
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
                                            val offline = machine.copy(connectionState = ConnectionState.Offline)
                                            upsertMachine(offline)
                                            statusMessage = "Connection failed: ${it.message}"
                                        }
                                }
                            },
                        )
                        AppTab.Settings -> PlaceholderScreen(padding, "Settings", "Settings will manage security, notifications, and bridge defaults.")
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            AddMachineCard(
                pairingLink = pairingLink,
                statusMessage = statusMessage,
                onPairingLinkChange = { pairingLink = it },
                onPairLink = { onPairLink(pairingLink) },
                onScanQr = onScanQr,
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Machines", style = MaterialTheme.typography.titleLarge)
                Text("${machines.size} paired", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(8.dp))
        }

        if (machines.isEmpty()) {
            item {
                Text("No machines paired yet.")
            }
        } else {
            items(machines, key = { it.id }) { machine ->
                MachineCard(machine = machine, onRefresh = { onRefreshMachine(machine) })
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun AddMachineCard(
    pairingLink: String,
    statusMessage: String?,
    onPairingLinkChange: (String) -> Unit,
    onPairLink: () -> Unit,
    onScanQr: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("Add Machine", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Scan the bridge QR code, or paste the acpclient://pair link.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onScanQr) {
                    Text("Scan QR")
                }
                OutlinedButton(
                    enabled = pairingLink.isNotBlank(),
                    onClick = onPairLink,
                ) {
                    Text("Pair Link")
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = pairingLink,
                onValueChange = onPairingLinkChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Pairing link fallback") },
                minLines = 2,
            )
            statusMessage?.let {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun MachineCard(machine: Machine, onRefresh: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(machine.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StateChip(machine.connectionState)
            }
            Spacer(Modifier.height(6.dp))
            Text(machine.endpoint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            machine.bridgeVersion?.let { Text("Bridge: $it") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRefresh) {
                Text("Test Connection")
            }
            if (machine.workspaces.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Workspaces", style = MaterialTheme.typography.titleSmall)
                machine.workspaces.forEach { workspace ->
                    Text("${workspace.displayName}: ${workspace.absolutePath}")
                }
            }
            if (machine.agents.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Agents", style = MaterialTheme.typography.titleSmall)
                machine.agents.forEach { agent ->
                    Text("${agent.displayName}: ${agent.status}")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StateChip(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.Online -> MaterialTheme.colorScheme.primaryContainer
        ConnectionState.Offline -> MaterialTheme.colorScheme.errorContainer
        ConnectionState.Unknown -> MaterialTheme.colorScheme.surfaceVariant
    }
    FilterChip(
        selected = false,
        onClick = {},
        label = { Text(state.name) },
        enabled = false,
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(disabledContainerColor = color),
    )
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun QrScannerScreen(onLinkScanned: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var scannerMessage by remember { mutableStateOf("Point the camera at the bridge QR code.") }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        scannerMessage = if (granted) "Point the camera at the bridge QR code." else "Camera permission is required to scan QR codes."
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Scan Pairing QR", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("The link stays local to pairing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onClose) {
                    Text("Close")
                }
            }
            Spacer(Modifier.height(16.dp))
            if (hasCameraPermission) {
                CameraScannerPreview(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onQrFound = onLinkScanned,
                )
            } else {
                PermissionPrompt(
                    modifier = Modifier.weight(1f),
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(scannerMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    val handled = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            scanner.close()
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                val previewView = PreviewView(viewContext)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(viewContext)
                cameraProviderFuture.addListener(
                    {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also {
                                it.setAnalyzer(cameraExecutor) { imageProxy ->
                                    if (handled.get()) {
                                        imageProxy.close()
                                    } else {
                                        scanQrCode(imageProxy, scanner) { value ->
                                            if (handled.compareAndSet(false, true)) {
                                                ContextCompat.getMainExecutor(context).execute {
                                                    onQrFound(value)
                                                }
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
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(220.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.03f)),
                    ),
                ),
        )
    }
}

@ExperimentalGetImage
private fun scanQrCode(
    imageProxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onQrFound: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull { it.rawValue?.startsWith("acpclient://pair") == true }?.rawValue?.let(onQrFound)
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

@Composable
private fun PermissionPrompt(modifier: Modifier, onRequestPermission: () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Camera access is needed to scan pairing QR codes.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRequestPermission) {
            Text("Grant Camera Permission")
        }
    }
}

@Composable
private fun PlaceholderScreen(padding: PaddingValues, title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body)
    }
}

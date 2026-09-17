package com.gongpx.androidacpclient.data.bridge

import android.os.Handler
import android.os.Looper
import com.gongpx.androidacpclient.data.model.Agent
import com.gongpx.androidacpclient.data.model.AgentSessionInfo
import com.gongpx.androidacpclient.data.model.ApprovalDecisionResult
import com.gongpx.androidacpclient.data.model.BridgeApprovalRequest
import com.gongpx.androidacpclient.data.model.ChatMessage
import com.gongpx.androidacpclient.data.model.ChatMessageKind
import com.gongpx.androidacpclient.data.model.ConnectionState
import com.gongpx.androidacpclient.data.model.HistoryPage
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.model.MessageRole
import com.gongpx.androidacpclient.data.model.QueuedPrompt
import com.gongpx.androidacpclient.data.model.PairingPayload
import com.gongpx.androidacpclient.data.model.Workspace
import com.gongpx.androidacpclient.data.model.toAgentPlanMessage
import com.gongpx.androidacpclient.data.model.bridgeConnectionFailure
import com.gongpx.androidacpclient.data.model.mergeToolActivity
import com.gongpx.androidacpclient.data.model.requireBridgeResult
import com.gongpx.androidacpclient.data.model.toApprovalDecisionResult
import com.gongpx.androidacpclient.data.model.toApprovalSnapshot
import com.gongpx.androidacpclient.data.model.toBridgeApprovalRequest
import com.gongpx.androidacpclient.data.model.toHistoryPage
import com.gongpx.androidacpclient.data.model.toToolActivity
import com.gongpx.androidacpclient.data.model.BridgeConnectionException
import com.gongpx.androidacpclient.data.model.BridgeConnectionFailureCategory
import com.gongpx.androidacpclient.data.tunnel.TunnelLoginRequired
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

class BridgeClient(
    private val accountHeaders: ((String, Map<String, String>) -> Map<String, String>)? = null,
) {
    private val webSocketClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            val endpoint = "${url.scheme}://${url.host}" + if (url.port == 443 || url.port == 80) "" else ":${url.port}"
            val headers = request.headers.names().associateWith { request.header(it).orEmpty() }
            val updated = resolveAccountHeaders(endpoint, headers)
            chain.proceed(request.newBuilder().apply {
                removeHeader("X-Tunnel-Authorization")
                updated.forEach { (key, value) -> header(key, value) }
            }.build())
        }
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun redeemPairing(payload: PairingPayload): Result<Machine> = withContext(Dispatchers.IO) {
        runCatching {
            val response = postJson(
                endpoint = payload.endpoint,
                path = "/pairing/redeem",
                headers = payload.headers,
                body = JSONObject()
                    .put("pairingId", payload.pairingId)
                    .put("pairingToken", payload.pairingToken)
                    .put(
                        "device",
                        JSONObject()
                            .put("name", android.os.Build.MODEL ?: "Android")
                            .put("platform", "android")
                            .put("appVersion", "0.1.0"),
                    ),
            )

            Machine(
                id = response.getString("machineId"),
                displayName = payload.machineName,
                endpoint = payload.endpoint,
                deviceToken = response.getString("deviceToken"),
                bridgeFingerprint = response.getString("bridgeFingerprint"),
                connectionHeaders = payload.headers,
            )
        }
    }

    suspend fun fetchMachineDetails(machine: Machine): Result<Machine> = withContext(Dispatchers.IO) {
        runCatching {
            val health = getJson(machine.endpoint, "/health", machine.connectionHeaders)
            val agents = getJson(machine.endpoint, "/agents", machine.connectionHeaders).getJSONArray("agents").toAgents()
            val workspaces = getJson(machine.endpoint, "/workspaces", machine.connectionHeaders).getJSONArray("workspaces").toWorkspaces()

            machine.copy(
                bridgeVersion = health.optString("bridgeVersion").ifBlank { null },
                connectionState = ConnectionState.Online,
                agents = agents,
                workspaces = workspaces,
            )
        }
    }

    suspend fun sendChatPrompt(
        machine: Machine,
        chatId: String,
        agentId: String,
        workspacePath: String,
        text: String,
        operationId: String = "op_" + UUID.randomUUID(),
        sessionId: String? = null,
        sessionResumable: Boolean = false,
        onMessage: (ChatMessage) -> Unit = {},
        onApproval: (BridgeApprovalRequest) -> Unit = {},
        onPromptAccepted: (String, String, String) -> Unit = { _, _, _ -> },
        onPromptStarted: (String, String) -> Unit = { _, _ -> },
        onOperationDone: (String, String, String, Int) -> Unit = { _, _, _, _ -> },
        onStatus: (String, Int?, Int, String, Boolean) -> Unit = { _, _, _, _, _ -> },
        onSession: (String, Boolean) -> Unit = { _, _ -> },
    ): BridgeSendResult<List<ChatMessage>> {
        return sendBridgeMessage(
            machine,
            JSONObject()
                .put("type", "chat.prompt")
                .put("operationId", operationId)
                .put("chatId", chatId)
                .put("agentId", agentId)
                .put("workspacePath", workspacePath)
                .put("content", text)
                .putSessionBinding(sessionId, sessionResumable),
            onMessage = onMessage,
            onApproval = onApproval,
            onEvent = { event ->
                when (event.optString("type")) {
                    "operation.accepted" -> if (event.optString("operationType") == "chat.prompt") {
                        mainHandler.post {
                            onPromptAccepted(
                                event.optString("operationId"),
                                event.optString("state"),
                                event.optString("content"),
                            )
                        }
                    }
                    "operation.started" -> mainHandler.post {
                        onPromptStarted(event.optString("operationId"), event.optString("content"))
                    }
                    "operation.done" -> mainHandler.post {
                        onOperationDone(
                            event.optString("operationType"),
                            event.optString("status"),
                            event.optString("operationId"),
                            event.optInt("queueRemaining", 0),
                        )
                    }
                    "chat.status" -> mainHandler.post {
                        val eventId = event.optInt("eventId", -1)
                        onStatus(
                            event.optString("status"),
                            eventId.takeIf { it >= 0 },
                            event.optInt("queuedCount", 0),
                            event.optString("operationId"),
                            event.optBoolean("snapshot", false),
                        )
                    }
                    "chat.session" -> mainHandler.post {
                        onSession(event.optString("sessionId"), event.optBoolean("resumable"))
                    }
                }
            },
            allowPartialOnFailure = true,
        )
    }

    suspend fun sendApprovalDecision(machine: Machine, approvalId: String, decision: String): BridgeSendResult<List<ChatMessage>> {
        return sendBridgeMessage(
            machine,
            JSONObject()
                .put("type", "approval.decide")
                .put("approvalId", approvalId)
                .put("decision", decision),
        )
    }

    suspend fun sendApprovalDecisionValidated(
        machine: Machine,
        approvalId: String,
        decision: String,
    ): Result<ApprovalDecisionResult> {
        if (decision !in setOf("approved", "denied")) {
            return Result.failure(IllegalArgumentException("Decision must be approved or denied."))
        }
        return sendRawBridgeMessage(
            machine,
            JSONObject()
                .put("type", "approval.decide")
                .put("approvalId", approvalId)
                .put("decision", decision),
        ).map { events ->
            requireBridgeResult(events, "approval.decide.result").toApprovalDecisionResult(approvalId, decision)
        }.result
    }

    suspend fun removeQueuedPrompt(machine: Machine, chatId: String, operationId: String): BridgeSendResult<String?> {
        return sendRawBridgeMessage(
            machine = machine,
            payload = JSONObject()
                .put("type", "chat.prompt.remove")
                .put("chatId", chatId)
                .put("operationId", operationId),
            allowPartialOnFailure = true,
        ).map { events ->
            events.lastOrNull {
                it.optString("type") == "operation.done" &&
                    it.optString("operationId") == operationId
            }?.optString("status")?.ifBlank { null }
        }
    }

    suspend fun listSessions(machine: Machine, agentId: String, workspacePath: String): Result<List<AgentSessionInfo>> {
        return sendRawBridgeMessage(
            machine,
            JSONObject()
                .put("type", "session.list")
                .put("agentId", agentId)
                .put("workspacePath", workspacePath),
        ).map { events ->
            val sessions = requireBridgeResult(events, "session.list.result").optJSONArray("sessions")
                ?: throw IOException("Bridge returned an invalid session list.")
            sessions.toSessionInfos()
        }.result
    }

    suspend fun loadSession(
        machine: Machine,
        chatId: String,
        agentId: String,
        workspacePath: String,
        sessionId: String,
        onSession: (String, Boolean) -> Unit = { _, _ -> },
    ): BridgeSendResult<List<ChatMessage>> {
        return sendBridgeMessage(
            machine,
            JSONObject()
                .put("type", "session.load")
                .put("chatId", chatId)
                .put("agentId", agentId)
                .put("workspacePath", workspacePath)
                .put("sessionId", sessionId),
            onEvent = { event ->
                if (event.optString("type") == "chat.session") {
                    mainHandler.post { onSession(event.optString("sessionId"), event.optBoolean("resumable")) }
                }
            },
            allowPartialOnFailure = true,
        )
    }

    suspend fun loadRecentSession(
        machine: Machine,
        chatId: String,
        agentId: String,
        workspacePath: String,
        sessionId: String,
        limit: Int,
        onSession: (String, Boolean) -> Unit = { _, _ -> },
    ): BridgeSendResult<List<ChatMessage>> {
        return loadRecentSessionPage(
            machine, chatId, agentId, workspacePath, sessionId, limit, onSession,
        ).map { it.messages }
    }

    suspend fun loadRecentSessionPage(
        machine: Machine,
        chatId: String,
        agentId: String,
        workspacePath: String,
        sessionId: String,
        limit: Int,
        onSession: (String, Boolean) -> Unit = { _, _ -> },
    ): BridgeSendResult<HistoryPage> {
        return sendRawBridgeMessage(
            machine,
            JSONObject()
                .put("type", "session.loadRecent")
                .put("chatId", chatId)
                .put("agentId", agentId)
                .put("workspacePath", workspacePath)
                .put("sessionId", sessionId)
                .put("limit", limit),
            allowPartialOnFailure = true,
            onEvent = { event ->
                if (event.optString("type") == "chat.session") {
                    mainHandler.post { onSession(event.optString("sessionId"), event.optBoolean("resumable")) }
                }
            },
        ).map { events ->
            requireBridgeResult(events, "session.loadRecent.result").toHistoryPage()
        }
    }

    suspend fun loadHistoryPage(
        machine: Machine,
        chatId: String,
        sessionId: String,
        historyId: String,
        before: Int,
        limit: Int,
    ): Result<HistoryPage> {
        return sendRawBridgeMessage(
            machine,
            JSONObject()
                .put("type", "session.history")
                .put("chatId", chatId)
                .put("sessionId", sessionId)
                .put("historyId", historyId)
                .put("before", before)
                .put("limit", limit),
        ).map { events ->
            requireBridgeResult(events, "session.history.result").toHistoryPage()
        }.result
    }

    suspend fun setConfigOption(
        machine: Machine,
        chatId: String,
        agentId: String,
        workspacePath: String,
        configId: String,
        value: String,
        sessionId: String? = null,
        sessionResumable: Boolean = false,
        onSession: (String, Boolean) -> Unit = { _, _ -> },
    ): BridgeSendResult<List<ChatMessage>> {
        return sendBridgeMessage(
            machine,
            JSONObject()
                .put("type", "session.setConfigOption")
                .put("chatId", chatId)
                .put("agentId", agentId)
                .put("workspacePath", workspacePath)
                .put("configId", configId)
                .put("value", value)
                .putSessionBinding(sessionId, sessionResumable),
            onEvent = { event ->
                if (event.optString("type") == "chat.session") {
                    mainHandler.post { onSession(event.optString("sessionId"), event.optBoolean("resumable")) }
                }
            },
        )
    }

    suspend fun refreshConfigOptions(
        machine: Machine,
        chatId: String,
        agentId: String,
        workspacePath: String,
        sessionId: String? = null,
        sessionResumable: Boolean = false,
        onSession: (String, Boolean) -> Unit = { _, _ -> },
    ): BridgeSendResult<List<ChatMessage>> {
        return sendBridgeMessage(
            machine,
            JSONObject()
                .put("type", "session.refreshConfigOptions")
                .put("chatId", chatId)
                .put("agentId", agentId)
                .put("workspacePath", workspacePath)
                .putSessionBinding(sessionId, sessionResumable),
            onEvent = { event ->
                if (event.optString("type") == "chat.session") {
                    mainHandler.post { onSession(event.optString("sessionId"), event.optBoolean("resumable")) }
                }
            },
            allowPartialOnFailure = true,
        )
    }

    fun openChatConnection(
        machine: Machine,
        chatId: String,
        agentId: String,
        workspacePath: String,
        lastEventId: Int,
        lastEventGeneration: String? = null,
        sessionId: String? = null,
        sessionResumable: Boolean = false,
        queuedPrompts: List<QueuedPrompt> = emptyList(),
        onMessage: (ChatMessage, Boolean) -> Unit = { _, _ -> },
        onApproval: (BridgeApprovalRequest, Boolean) -> Unit = { _, _ -> },
        onStatus: (String, Int?, Int, String, Boolean) -> Unit = { _, _, _, _, _ -> },
        onPromptAccepted: (String, String, String, Boolean) -> Unit = { _, _, _, _ -> },
        onPromptStarted: (String, String, Boolean) -> Unit = { _, _, _ -> },
        onOperationDone: (String, String, String, Int, Boolean) -> Unit = { _, _, _, _, _ -> },
        onSession: (String, Boolean, Boolean) -> Unit = { _, _, _ -> },
        onEventId: (Int) -> Unit = {},
        onEventGeneration: (String, Boolean) -> Unit = { _, _ -> },
        onResyncRequired: () -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
        onApprovalSnapshot: (List<BridgeApprovalRequest>) -> Unit = {},
        onApprovalResolved: (String, String, Long) -> Unit = { _, _, _ -> },
        chatTitle: String? = null,
    ): ChatConnection {
        val requestBuilder = Request.Builder().url(toWebSocketUrl(machine.endpoint, machine.deviceToken))
        machine.connectionHeaders.forEach { (name, value) ->
            requestBuilder.addHeader(name, value)
        }
        var socket: WebSocket? = null
        val sendLock = Any()
        val pendingPayloads = mutableListOf<JSONObject>()
        var socketReady = false
        var attachReplayBoundary: Int? = null
        val intentionallyClosed = AtomicBoolean(false)
        val terminationNotified = AtomicBoolean(false)
        fun notifyConnectionFailure(error: Throwable) {
            synchronized(sendLock) {
                socketReady = false
            }
            if (!intentionallyClosed.get() && terminationNotified.compareAndSet(false, true)) {
                mainHandler.post {
                    if (!intentionallyClosed.get()) onFailure(error.withReadableMessage())
                }
            }
        }
        val connection = ChatConnection(chatId = chatId) { payload ->
            synchronized(sendLock) {
                if (intentionallyClosed.get()) {
                    false
                } else if (socketReady) {
                    socket?.send(payload.toString()) == true
                } else {
                    pendingPayloads.add(payload)
                    true
                }
            }
        }
        socket = webSocketClient.newWebSocket(
            requestBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    synchronized(sendLock) {
                        if (intentionallyClosed.get()) {
                            webSocket.cancel()
                            return
                        }
                        webSocket.send(
                            JSONObject()
                                .put("type", "chat.attach")
                                .put("chatId", chatId)
                                .put("chatTitle", chatTitle?.take(256))
                                .put("agentId", agentId)
                                .put("workspacePath", workspacePath)
                                .put("lastEventId", lastEventId)
                                .apply {
                                    if (lastEventGeneration != null) {
                                        put("lastEventGeneration", lastEventGeneration)
                                    }
                                }
                                .putSessionBinding(sessionId, sessionResumable)
                                .toString(),
                        )
                        val pendingRemovals = pendingPayloads.filter {
                            it.optString("type") == "chat.prompt.remove"
                        }
                        val pendingMessages = pendingPayloads.filterNot {
                            it.optString("type") == "chat.prompt.remove"
                        }
                        queuedPrompts.filter { it.removing }.forEach { queued ->
                            webSocket.send(
                                JSONObject()
                                    .put("type", "chat.prompt.remove")
                                    .put("chatId", chatId)
                                    .put("operationId", queued.operationId)
                                    .toString(),
                            )
                        }
                        pendingRemovals.forEach { pending -> webSocket.send(pending.toString()) }
                        queuedPrompts.filterNot { it.removing }.forEach { queued ->
                            webSocket.send(
                                JSONObject()
                                    .put("type", "chat.prompt")
                                    .put("operationId", queued.operationId)
                                    .put("chatId", chatId)
                                    .put("agentId", agentId)
                                    .put("workspacePath", workspacePath)
                                    .put("content", queued.text)
                                    .putSessionBinding(sessionId, sessionResumable)
                                    .toString(),
                            )
                        }
                        pendingMessages.forEach { pending -> webSocket.send(pending.toString()) }
                        socketReady = true
                        pendingPayloads.clear()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (intentionallyClosed.get()) return
                    val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                    val eventId = event.optInt("eventId", -1)
                    val replayBoundary = attachReplayBoundary
                    val isReplay = replayBoundary != null && eventId in 0..replayBoundary
                    fun postApplied(action: () -> Unit = {}) {
                        mainHandler.post {
                            if (intentionallyClosed.get()) return@post
                            action()
                            if (!intentionallyClosed.get() && eventId >= 0) onEventId(eventId)
                        }
                    }
                    when (event.optString("type")) {
                        "bridge.accepted", "bridge.heartbeat" -> postApplied()
                        "chat.attached" -> {
                            val latestEventId = event.optInt("latestEventId", 0)
                            attachReplayBoundary = latestEventId
                            val eventGeneration = event.optString("eventGeneration")
                            val checkpointReset = event.optBoolean(
                                "checkpointReset",
                                latestEventId < lastEventId ||
                                    (lastEventGeneration != null && eventGeneration != lastEventGeneration),
                            )
                            if (eventGeneration.isNotBlank()) {
                                mainHandler.post {
                                    if (!intentionallyClosed.get()) onEventGeneration(eventGeneration, checkpointReset)
                                }
                            }
                        }
                        "operation.accepted" -> if (event.optString("operationType") == "chat.prompt") {
                            postApplied {
                                onPromptAccepted(
                                    event.optString("operationId"),
                                    event.optString("state"),
                                    event.optString("content"),
                                    isReplay,
                                )
                            }
                        } else {
                            postApplied()
                        }
                        "operation.started" -> postApplied {
                            onPromptStarted(event.optString("operationId"), event.optString("content"), isReplay)
                        }
                        "operation.done" -> postApplied {
                            onOperationDone(
                                event.optString("operationType"),
                                event.optString("status"),
                                event.optString("operationId"),
                                event.optInt("queueRemaining", 0),
                                isReplay,
                            )
                        }
                        "chat.status" -> {
                            val isSnapshot = event.optBoolean(
                                "snapshot",
                                replayBoundary != null && eventId > replayBoundary,
                            )
                            if (isSnapshot) attachReplayBoundary = null
                            postApplied {
                                onStatus(
                                    event.optString("status"),
                                    eventId.takeIf { it >= 0 },
                                    event.optInt("queuedCount", 0),
                                    event.optString("operationId"),
                                    isSnapshot,
                                )
                            }
                        }
                        "chat.session" -> postApplied {
                            onSession(event.optString("sessionId"), event.optBoolean("resumable"), isReplay)
                        }
                        "chat.resyncRequired" -> postApplied { onResyncRequired() }
                        "approval.requested" -> {
                            val request = event.toBridgeApprovalRequest()
                            postApplied { if (request != null) onApproval(request, isReplay) }
                        }
                        "approval.snapshot" -> {
                            val approvals = runCatching { event.toApprovalSnapshot() }.getOrElse {
                                notifyConnectionFailure(bridgeConnectionFailure())
                                webSocket.cancel()
                                return
                            }
                            postApplied { onApprovalSnapshot(approvals) }
                        }
                        "approval.resolved" -> postApplied {
                            onApprovalResolved(
                                event.optString("approvalId"),
                                event.optString("status"),
                                event.optLong("decidedAt", 0),
                            )
                        }
                        else -> {
                            val message = parseBridgeMessage(event.toString()).message
                            postApplied { if (message != null) onMessage(message, isReplay) }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    notifyConnectionFailure(t.asConnectionFailure(response?.code))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                    notifyConnectionFailure(bridgeConnectionFailure())
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    notifyConnectionFailure(bridgeConnectionFailure())
                }
            },
        )
        connection.setCloseHandler {
            intentionallyClosed.set(true)
            synchronized(sendLock) {
                socketReady = false
                pendingPayloads.clear()
                socket?.cancel()
            }
        }
        return connection
    }

    private fun getJson(endpoint: String, path: String, headers: Map<String, String>): JSONObject {
        val connection = URL(toHttpBase(endpoint) + path).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setConnectionHeaders(resolveAccountHeaders(endpoint, headers))
        return connection.useJsonResponse()
    }

    private fun postJson(endpoint: String, path: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val connection = URL(toHttpBase(endpoint) + path).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.doOutput = true
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setConnectionHeaders(if (path == "/pairing/redeem") headers else resolveAccountHeaders(endpoint, headers))
        connection.outputStream.use { stream ->
            stream.write(body.toString().toByteArray(Charsets.UTF_8))
        }
        return connection.useJsonResponse()
    }

    private suspend fun sendBridgeMessage(
        machine: Machine,
        payload: JSONObject,
        onMessage: (ChatMessage) -> Unit = {},
        onApproval: (BridgeApprovalRequest) -> Unit = {},
        onEvent: (JSONObject) -> Unit = {},
        allowPartialOnFailure: Boolean = false,
    ): BridgeSendResult<List<ChatMessage>> {
        return sendRawBridgeMessage(machine, payload, allowPartialOnFailure = allowPartialOnFailure) { event ->
            onEvent(event)
            if (event.optString("type") == "approval.requested") {
                event.toBridgeApprovalRequest()?.let { request ->
                    mainHandler.post { onApproval(request) }
                }
            } else {
                parseBridgeMessage(event.toString()).message?.let { message ->
                    mainHandler.post { onMessage(message) }
                }
            }
        }.map { events ->
            val messages = mutableListOf<ChatMessage>()
            events.forEach { event ->
                if (event.optString("type") != "approval.requested") {
                    parseBridgeMessage(event.toString()).message?.let { message ->
                        mergeStreamingMessage(messages, message)
                    }
                }
            }
            messages.toList()
        }
    }

    private suspend fun sendRawBridgeMessage(
        machine: Machine,
        payload: JSONObject,
        allowPartialOnFailure: Boolean = false,
        onEvent: (JSONObject) -> Unit = {},
    ): BridgeSendResult<List<JSONObject>> {
        return suspendCancellableCoroutine { continuation ->
            val requestBuilder = Request.Builder().url(toWebSocketUrl(machine.endpoint, machine.deviceToken))
            machine.connectionHeaders.forEach { (name, value) ->
                requestBuilder.addHeader(name, value)
            }
            val events = mutableListOf<JSONObject>()
            var accepted = false

            fun completeWithPartialOrFailure(t: Throwable) {
                if (!continuation.isActive) return
                if (allowPartialOnFailure && (accepted || events.isNotEmpty())) {
                    continuation.resume(BridgeSendResult(Result.success(events.toList()), accepted = accepted))
                } else {
                    continuation.resume(BridgeSendResult(Result.failure(t.withReadableMessage()), accepted = accepted))
                }
            }

            fun completeOnClose() {
                if (!continuation.isActive) return
                if (allowPartialOnFailure && (accepted || events.isNotEmpty())) {
                    continuation.resume(BridgeSendResult(Result.success(events.toList()), accepted = accepted))
                } else {
                    continuation.resume(BridgeSendResult(Result.failure(IOException("WebSocket closed before bridge.done")), accepted = accepted))
                }
            }

            var socket: WebSocket? = null
            socket = webSocketClient.newWebSocket(
                requestBuilder.build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(payload.toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val json = runCatching { JSONObject(text) }.getOrNull()
                        if (json?.optString("type") == "bridge.accepted") {
                            accepted = true
                            return
                        }
                        if (json?.optString("type") == "bridge.heartbeat") {
                            return
                        }
                        if (json?.optString("type") == "bridge.done") {
                            if (continuation.isActive) {
                                continuation.resume(BridgeSendResult(Result.success(events.toList()), accepted = accepted))
                            }
                            webSocket.close(1000, "done")
                            return
                        }
                        val event = json ?: JSONObject().put("type", "bridge.text").put("text", text)
                        events.add(event)
                        onEvent(event)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        completeWithPartialOrFailure(t.asConnectionFailure(response?.code))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        completeOnClose()
                    }
                },
            )
            continuation.invokeOnCancellation {
                socket?.cancel()
            }
        }
    }

    private fun HttpURLConnection.setConnectionHeaders(headers: Map<String, String>) {
        headers.forEach { (name, value) ->
            setRequestProperty(name, value)
        }
    }

    private fun Throwable.asConnectionFailure(httpStatus: Int?): BridgeConnectionException =
        if (this is TunnelLoginRequired) BridgeConnectionException(
            BridgeConnectionFailureCategory.Authentication, message ?: "Sign in again from Machines.",
        ) else bridgeConnectionFailure(httpStatus)

    private fun resolveAccountHeaders(endpoint: String, headers: Map<String, String>): Map<String, String> = try {
        accountHeaders?.invoke(endpoint, headers) ?: headers
    } catch (_: org.json.JSONException) {
        throw IOException("The account service returned invalid tunnel credentials.")
    } catch (_: IllegalArgumentException) {
        throw IOException("The account service returned an unsupported tunnel address.")
    } catch (_: IllegalStateException) {
        throw IOException("The account credentials could not be saved securely.")
    }

    private fun HttpURLConnection.useJsonResponse(): JSONObject {
        return try {
            val status = responseCode
            val stream = if (status in 200..299) inputStream else errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IOException("Bridge returned HTTP $status: $text")
            }
            JSONObject(text)
        } finally {
            disconnect()
        }
    }

    private fun toHttpBase(endpoint: String): String {
        return when {
            endpoint.startsWith("ws://") -> "http://" + endpoint.removePrefix("ws://")
            endpoint.startsWith("wss://") -> "https://" + endpoint.removePrefix("wss://")
            else -> endpoint.trimEnd('/')
        }.trimEnd('/')
    }

    private fun toWebSocketUrl(endpoint: String, token: String): String {
        val encodedToken = URLEncoder.encode(token, Charsets.UTF_8.name())
        return endpoint.trimEnd('/') + "/ws?token=" + encodedToken
    }

    private fun parseBridgeMessage(text: String): ParsedBridgeMessage {
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return ParsedBridgeMessage(message = ChatMessage(MessageRole.Agent, text, System.currentTimeMillis()))
        if (json.optString("type") == "bridge.done") {
            return ParsedBridgeMessage(done = true)
        }
        if (json.optString("type") in setOf(
                "bridge.accepted",
                "bridge.heartbeat",
                "chat.attached",
                "chat.status",
                "chat.session",
                "operation.accepted",
                "operation.started",
                "operation.done",
                "approval.snapshot",
                "approval.resolved",
            )
        ) {
            return ParsedBridgeMessage()
        }

        val update = json.optJSONObject("update")
        if (json.optString("type") == "session/update" && update != null) {
            return ParsedBridgeMessage(
                message = update.toChatMessage()?.copy(
                    operationId = (json.opt("operationId") as? String)?.takeIf { it.isNotBlank() },
                ),
            )
        }

        return ParsedBridgeMessage(
            message = ChatMessage(
                role = MessageRole.Agent,
                text = json.toString(),
                timestampMillis = System.currentTimeMillis(),
                kind = ChatMessageKind.Activity,
                title = json.optString("type", "Bridge event"),
                details = json.toString(2),
                operationId = (json.opt("operationId") as? String)?.takeIf { it.isNotBlank() },
            ),
        )
    }

    private fun JSONObject.toChatMessage(): ChatMessage? {
        val sessionUpdate = optString("sessionUpdate")
        return when (sessionUpdate) {
            "usage_update" -> null
            "config_option_update" -> {
                ChatMessage(
                    role = MessageRole.System,
                    text = "Config updated",
                    timestampMillis = System.currentTimeMillis(),
                    kind = ChatMessageKind.ConfigUpdate,
                    details = optJSONArray("configOptions")?.toString().orEmpty(),
                    activityId = "config_options",
                )
            }

            "available_commands_update" -> {
                ChatMessage(
                    role = MessageRole.System,
                    text = "Commands updated",
                    timestampMillis = System.currentTimeMillis(),
                    kind = ChatMessageKind.CommandUpdate,
                    details = optJSONArray("availableCommands")?.toString().orEmpty(),
                    activityId = "available_commands",
                )
            }
            "plan" -> toAgentPlanMessage(System.currentTimeMillis())
            "user_message_chunk" -> {
                val content = optJSONObject("content")
                val text = content?.optString("text").orEmpty().ifBlank { optString("text") }
                if (text.isBlank()) return null
                ChatMessage(
                    role = MessageRole.User,
                    text = text,
                    timestampMillis = System.currentTimeMillis(),
                    activityId = optString("messageId").ifBlank { "user_message" },
                )
            }
            "tool_call", "tool_call_update" -> toToolActivity(System.currentTimeMillis())
            "agent_message_chunk" -> {
                val content = optJSONObject("content")
                val text = content?.optString("text").orEmpty().ifBlank { optString("text") }
                if (text.isBlank()) return null
                ChatMessage(
                    role = MessageRole.Agent,
                    text = text,
                    timestampMillis = System.currentTimeMillis(),
                    activityId = optString("messageId").ifBlank { "agent_message" },
                )
            }
            "agent_thought_chunk" -> {
                val content = optJSONObject("content")
                val text = content?.optString("text").orEmpty().ifBlank { optString("text") }
                if (text.isBlank()) return null
                ChatMessage(
                    role = MessageRole.Agent,
                    text = "Thinking",
                    timestampMillis = System.currentTimeMillis(),
                    kind = ChatMessageKind.Activity,
                    title = "Thought",
                    details = text,
                    activityId = optString("messageId").ifBlank { "agent_thought" },
                )
            }
            else -> ChatMessage(
                role = MessageRole.Agent,
                text = sessionUpdate.ifBlank { "Agent update" },
                timestampMillis = System.currentTimeMillis(),
                kind = ChatMessageKind.Activity,
                title = sessionUpdate.ifBlank { "Agent update" },
                details = toString(2),
            )
        }
    }

    private fun mergeStreamingMessage(messages: MutableList<ChatMessage>, message: ChatMessage) {
        val streamId = message.activityId
        if (streamId == null) {
            messages.add(message)
            return
        }

        val existingIndex = if (message.kind == ChatMessageKind.Message) {
            messages.indexOfLast {
                it.activityId == streamId &&
                    (message.operationId == null || it.operationId == message.operationId)
            }
        } else {
            messages.indexOfFirst { it.activityId == streamId }
        }
        if (existingIndex < 0) {
            messages.add(message)
            return
        }
        if (message.kind == ChatMessageKind.Message && existingIndex != messages.lastIndex) {
            messages.add(message)
            return
        }

        val existing = messages[existingIndex]
        messages[existingIndex] = when {
            message.kind == ChatMessageKind.Activity && existing.kind == ChatMessageKind.Activity -> {
                if (existing.title == "Thought" && message.title == "Thought") {
                    existing.copy(details = listOfNotNull(existing.details, message.details).joinToString(""))
                } else {
                    mergeToolActivity(existing, message)
                }
            }
            message.kind == ChatMessageKind.Message && existing.kind == ChatMessageKind.Message -> {
                existing.copy(
                    text = existing.text + message.text,
                    operationId = message.operationId ?: existing.operationId,
                )
            }
            else -> message
        }
    }

    private fun JSONArray.toAgents(): List<Agent> {
        return List(length()) { index ->
            val item = getJSONObject(index)
            Agent(
                id = item.getString("id"),
                displayName = item.getString("displayName"),
                status = item.getString("status"),
            )
        }
    }

    private fun JSONArray.toWorkspaces(): List<Workspace> {
        return List(length()) { index ->
            val item = getJSONObject(index)
            Workspace(
                id = item.getString("id"),
                displayName = item.getString("displayName"),
                absolutePath = item.getString("absolutePath"),
            )
        }
    }

    private fun JSONArray.toSessionInfos(): List<AgentSessionInfo> {
        return List(length()) { index ->
            val item = getJSONObject(index)
            AgentSessionInfo(
                sessionId = item.getString("sessionId"),
                title = item.optString("title").ifBlank { null },
                cwd = item.optString("cwd").ifBlank { null },
                updatedAt = item.optString("updatedAt").ifBlank { null },
            )
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}

private fun JSONObject.putSessionBinding(sessionId: String?, resumable: Boolean): JSONObject {
    if (!sessionId.isNullOrBlank()) {
        put("sessionId", sessionId)
        put("sessionResumable", resumable)
    }
    return this
}

class ChatConnection internal constructor(
    val chatId: String,
    private val sendJson: (JSONObject) -> Boolean,
) {
    private var closeHandler: (() -> Unit)? = null

    fun sendPrompt(
        operationId: String,
        agentId: String,
        workspacePath: String,
        text: String,
        sessionId: String?,
        sessionResumable: Boolean,
    ): Boolean {
        return sendJson(
            JSONObject()
                .put("type", "chat.prompt")
                .put("operationId", operationId)
                .put("chatId", chatId)
                .put("agentId", agentId)
                .put("workspacePath", workspacePath)
                .put("content", text)
                .putSessionBinding(sessionId, sessionResumable),
        )
    }

    fun removeQueuedPrompt(operationId: String): Boolean {
        return sendJson(
            JSONObject()
                .put("type", "chat.prompt.remove")
                .put("chatId", chatId)
                .put("operationId", operationId),
        )
    }

    fun close() {
        closeHandler?.invoke()
    }

    internal fun setCloseHandler(handler: () -> Unit) {
        closeHandler = handler
    }
}

data class BridgeSendResult<T>(
    val result: Result<T>,
    val accepted: Boolean = false,
)

private inline fun <T, R> BridgeSendResult<T>.map(transform: (T) -> R): BridgeSendResult<R> {
    return BridgeSendResult(result.mapCatching(transform), accepted = accepted)
}

private fun Throwable.withReadableMessage(): Throwable {
    val readable = message ?: localizedMessage ?: javaClass.simpleName.ifBlank { "WebSocket connection failed" }
    return if (message.isNullOrBlank()) IOException(readable, this) else this
}

private data class ParsedBridgeMessage(
    val message: ChatMessage? = null,
    val done: Boolean = false,
)

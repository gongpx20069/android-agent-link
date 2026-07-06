package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.Agent
import com.gongpx.androidacpclient.data.model.ChatMessage
import com.gongpx.androidacpclient.data.model.ChatMessageKind
import com.gongpx.androidacpclient.data.model.ConnectionState
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.model.MessageRole
import com.gongpx.androidacpclient.data.model.PairingPayload
import com.gongpx.androidacpclient.data.model.Workspace
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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

class BridgeClient {
    private val webSocketClient = OkHttpClient()

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

    suspend fun sendChatPrompt(machine: Machine, chatId: String, agentId: String, workspacePath: String, text: String): Result<List<ChatMessage>> {
        return sendBridgeMessage(
            machine,
            JSONObject()
                .put("type", "chat.prompt")
                .put("chatId", chatId)
                .put("agentId", agentId)
                .put("workspacePath", workspacePath)
                .put("content", text),
        )
    }

    suspend fun sendApprovalDecision(machine: Machine, approvalId: String, decision: String): Result<List<ChatMessage>> {
        return sendBridgeMessage(
            machine,
            JSONObject()
                .put("type", "approval.decide")
                .put("approvalId", approvalId)
                .put("decision", decision),
        )
    }

    private fun getJson(endpoint: String, path: String, headers: Map<String, String>): JSONObject {
        val connection = URL(toHttpBase(endpoint) + path).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setConnectionHeaders(headers)
        return connection.useJsonResponse()
    }

    private fun postJson(endpoint: String, path: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val connection = URL(toHttpBase(endpoint) + path).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setConnectionHeaders(headers)
        connection.outputStream.use { stream ->
            stream.write(body.toString().toByteArray(Charsets.UTF_8))
        }
        return connection.useJsonResponse()
    }

    private suspend fun sendBridgeMessage(machine: Machine, payload: JSONObject): Result<List<ChatMessage>> {
        return suspendCancellableCoroutine { continuation ->
            val requestBuilder = Request.Builder().url(toWebSocketUrl(machine.endpoint, machine.deviceToken))
            machine.connectionHeaders.forEach { (name, value) ->
                requestBuilder.addHeader(name, value)
            }
            val messages = mutableListOf<ChatMessage>()

            var socket: WebSocket? = null
            socket = webSocketClient.newWebSocket(
                requestBuilder.build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(payload.toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val parsed = parseBridgeMessage(text)
                        if (parsed.done) {
                            if (continuation.isActive) {
                                continuation.resume(Result.success(messages.toList()))
                            }
                            webSocket.close(1000, "done")
                            return
                        }
                        parsed.message?.let { message ->
                            if (message.kind == ChatMessageKind.Activity && message.activityId != null) {
                                val existingIndex = messages.indexOfFirst { it.activityId == message.activityId }
                                if (existingIndex >= 0) {
                                    messages[existingIndex] = message
                                } else {
                                    messages.add(message)
                                }
                            } else {
                                messages.add(message)
                            }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(t))
                        }
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

        val update = json.optJSONObject("update")
        if (json.optString("type") == "session/update" && update != null) {
            return ParsedBridgeMessage(message = update.toChatMessage())
        }

        return ParsedBridgeMessage(
            message = ChatMessage(
                role = MessageRole.Agent,
                text = json.toString(),
                timestampMillis = System.currentTimeMillis(),
                kind = ChatMessageKind.Activity,
                title = json.optString("type", "Bridge event"),
                details = json.toString(2),
            ),
        )
    }

    private fun JSONObject.toChatMessage(): ChatMessage? {
        val sessionUpdate = optString("sessionUpdate")
        return when (sessionUpdate) {
            "available_commands_update", "config_option_update", "usage_update" -> null
            "tool_call", "tool_call_update" -> {
                val content = optJSONObject("content")
                val status = optString("status").ifBlank { if (sessionUpdate == "tool_call") "started" else "updated" }
                val title = optString("title").ifBlank { optString("kind").ifBlank { "Tool call" } }
                ChatMessage(
                    role = MessageRole.Agent,
                    text = "$title · $status",
                    timestampMillis = System.currentTimeMillis(),
                    kind = ChatMessageKind.Activity,
                    title = title,
                    activityId = optString("toolCallId").ifBlank { null },
                    details = JSONObject()
                        .put("sessionUpdate", sessionUpdate)
                        .put("toolCallId", optString("toolCallId"))
                        .put("kind", optString("kind"))
                        .put("status", status)
                        .put("content", content ?: JSONObject())
                        .toString(2),
                )
            }
            "agent_message_chunk" -> {
                val content = optJSONObject("content")
                ChatMessage(
                    role = MessageRole.Agent,
                    text = content?.optString("text").orEmpty().ifBlank { optString("text").ifBlank { toString() } },
                    timestampMillis = System.currentTimeMillis(),
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

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}

private data class ParsedBridgeMessage(
    val message: ChatMessage? = null,
    val done: Boolean = false,
)

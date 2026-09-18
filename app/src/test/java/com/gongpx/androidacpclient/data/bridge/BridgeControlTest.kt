package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.Machine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BridgeControlTest {
    @Test fun sendReturnsAcknowledgedTaskWithoutWaitingForAgentCompletion() = runBlocking {
        val server = MockWebServer()
        var frame: JSONObject? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = JSONObject(text)
                frame = request
                webSocket.send(JSONObject().put("type", "control.result").put("requestId", request.getString("requestId"))
                    .put("status", "ok").put("data", JSONObject().put("taskId", "task-1").put("state", "queued")).toString())
                webSocket.send(JSONObject().put("type", "bridge.done").toString())
            }
        }))
        server.start()
        try {
            val machine = Machine("m", "Machine", server.url("/").toString(), "test-token", "fingerprint")
            val result = withTimeout(5_000) {
                BridgeClient().controlRequest(machine, "chat.send", JSONObject().put("chatId", "c")
                    .put("operationId", "op").put("source", "mochi").put("expectedHumanRevision", 2))
            }
            assertEquals("queued", result.getJSONObject("data").getString("state"))
            assertEquals("control.request", frame!!.getString("type"))
            assertEquals("chat.send", frame!!.getString("action"))
            assertEquals("c", frame!!.getString("chatId"))
        } finally { server.shutdown() }
    }

    @Test fun conflictIsReturnedWithoutAutomaticWriteRetry() = runBlocking {
        val server = MockWebServer()
        var requests = 0
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                requests++
                val id = JSONObject(text).getString("requestId")
                webSocket.send(JSONObject().put("type", "control.result").put("requestId", id)
                    .put("status", "error").put("code", "CONFLICT").put("message", "Human revision changed.").toString())
                webSocket.send(JSONObject().put("type", "bridge.done").toString())
            }
        }))
        server.start()
        try {
            val result = withTimeout(5_000) {
                BridgeClient().controlRequest(Machine("m", "M", server.url("/").toString(), "token", "f"), "chat.send")
            }
            assertEquals("CONFLICT", result.getString("code"))
            assertEquals(1, requests)
        } finally { server.shutdown() }
    }
}

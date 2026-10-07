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
    @Test fun catalogPreservesExplicitDeletionRowsAcrossPages() = runBlocking {
        val server = MockWebServer()
        val frames = mutableListOf<JSONObject>()
        for (offset in 0..1) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = JSONObject(text)
                    frames.add(frame)
                    val row = JSONObject().put("chatId", if (offset == 0) "live" else "removed")
                    if (offset == 1) row.put("deleted", true)
                    val data = JSONObject().put("chats", org.json.JSONArray().put(row))
                        .put("hasMore", offset == 0).put("nextOffset", offset + 1)
                    webSocket.send(JSONObject().put("type", "control.result").put("requestId", frame.getString("requestId"))
                        .put("status", "ok").put("data", data).toString())
                    webSocket.send("""{"type":"bridge.done"}""")
                }
            }))
        }
        server.start()
        try {
            val rows = withTimeout(5_000) {
                BridgeClient().listSharedChats(Machine("m", "M", server.url("/").toString(), "token", "f"))
            }
            assertEquals(listOf("live", "removed"), rows.map { it.getString("chatId") })
            assertTrue(rows.last().getBoolean("deleted"))
            assertTrue(frames.all { it.getBoolean("includeDeleted") })
            assertEquals(listOf(0, 1), frames.map { it.getInt("offset") })
        } finally { server.shutdown() }
    }

    @Test fun sharedDeletionRequiresExactPositiveConfirmationAndNeverRetries() = runBlocking {
        val server = MockWebServer()
        val frames = mutableListOf<JSONObject>()
        val responses = listOf(
            JSONObject().put("status", "ok").put("data", JSONObject().put("chatId", "chat").put("deleted", true)),
            JSONObject().put("status", "ok").put("data", JSONObject().put("chatId", "other").put("deleted", true)),
            JSONObject().put("status", "ok").put("data", JSONObject().put("chatId", "chat").put("deleted", false)),
            JSONObject().put("status", "error").put("code", "CONFLICT").put("message", "Chat is busy"),
            JSONObject().put("status", "error").put("code", "UNSUPPORTED").put("message", "Old Bridge"),
        )
        responses.forEach { response ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = JSONObject(text)
                    frames.add(frame)
                    webSocket.send(response.put("type", "control.result")
                        .put("requestId", frame.getString("requestId")).toString())
                    webSocket.send("""{"type":"bridge.done"}""")
                }
            }))
        }
        server.start()
        try {
            val machine = Machine("m", "M", server.url("/").toString(), "token", "f")
            withTimeout(5_000) {
                BridgeClient().deleteSharedChat(machine, "chat")
                repeat(4) {
                    try {
                        BridgeClient().deleteSharedChat(machine, "chat")
                        fail("Unconfirmed deletion must fail")
                    } catch (_: IllegalStateException) { }
                }
            }
            assertEquals(5, frames.size)
            assertTrue(frames.all { it.getString("action") == "chat.delete" && it.getString("chatId") == "chat" })
        } finally { server.shutdown() }
    }

    @Test fun cancellationValidatesTaskIdentityAndDoesNotTreatRequestAsCompletion() = runBlocking {
        val server = MockWebServer()
        val frames = mutableListOf<JSONObject>()
        for (task in listOf("active", "wrong")) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = JSONObject(text)
                    frames.add(frame)
                    webSocket.send(JSONObject().put("type", "control.result").put("requestId", frame.getString("requestId"))
                        .put("status", "ok").put("data", JSONObject().put("taskId", task).put("state", "cancellation_requested")).toString())
                    webSocket.send("""{"type":"bridge.done"}""")
                }
            }))
        }
        server.start()
        try {
            val machine = Machine("m", "M", server.url("/").toString(), "token", "f")
            withTimeout(5_000) {
                assertEquals("cancellation_requested", BridgeClient().cancelTask(machine, "chat", "active"))
                try {
                    BridgeClient().cancelTask(machine, "chat", "active")
                    fail("A different task must not be acknowledged")
                } catch (_: java.io.IOException) { }
            }
            assertEquals(listOf("task.cancel", "task.cancel"), frames.map { it.getString("action") })
            assertTrue(frames.all { it.getString("operationId") == "active" && it.getString("chatId") == "chat" })
        } finally { server.shutdown() }
    }

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

package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.Machine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.Response
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
class BridgeDurabilityTest {
    @Test fun neitherAttachNorPromptCanPassTheDurabilityBarrier() {
        val server = MockWebServer()
        val frames = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { frames.add(text) }
        }))
        server.start()
        val entered = CountDownLatch(1)
        val durable = CountDownLatch(1)
        val client = BridgeClient(beforeSend = {
            entered.countDown()
            check(durable.await(5, TimeUnit.SECONDS)) { "Persistence timed out" }
        })
        val connection = client.openChatConnection(
            machine = Machine("m", "Machine", server.url("/").toString(), "test-token", "fingerprint"),
            chatId = "chat", agentId = "agent", workspacePath = "workspace", lastEventId = 12,
        )
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(connection.sendPrompt("operation", "agent", "workspace", "hello", null, false))
            assertNull(frames.poll(100, TimeUnit.MILLISECONDS))
            durable.countDown()
            val attach = JSONObject(requireNotNull(frames.poll(3, TimeUnit.SECONDS)))
            val prompt = JSONObject(requireNotNull(frames.poll(3, TimeUnit.SECONDS)))
            assertEquals("chat.attach", attach.getString("type"))
            assertEquals(12, attach.getInt("lastEventId"))
            assertEquals("chat.prompt", prompt.getString("type"))
            assertEquals("operation", prompt.getString("operationId"))
            assertEquals("hello", prompt.getString("content"))
        } finally {
            durable.countDown()
            connection.close()
            server.shutdown()
        }
    }

    @Test fun failedPersistenceSendsNoPromptOrAttach() {
        val server = MockWebServer()
        val opened = CountDownLatch(1)
        val frames = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
            override fun onMessage(webSocket: WebSocket, text: String) { frames.add(text) }
        }))
        server.start()
        val client = BridgeClient(beforeSend = { error("Storage failed") })
        val connection = client.openChatConnection(
            machine = Machine("m", "Machine", server.url("/").toString(), "test-token", "fingerprint"),
            chatId = "chat", agentId = "agent", workspacePath = "workspace", lastEventId = 12,
        )
        try {
            assertTrue(opened.await(3, TimeUnit.SECONDS))
            connection.sendPrompt("operation", "agent", "workspace", "must not send", null, false)
            assertNull(frames.poll(200, TimeUnit.MILLISECONDS))
        } finally {
            connection.close()
            server.shutdown()
        }
    }
}

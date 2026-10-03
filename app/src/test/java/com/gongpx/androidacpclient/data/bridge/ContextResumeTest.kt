package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.*
import com.gongpx.androidacpclient.data.store.ChatJsonCodec.toChat
import com.gongpx.androidacpclient.data.store.ChatJsonCodec.toJson
import java.io.IOException
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
class ContextResumeTest {
    private val machine = Machine("m", "Machine", "ws://localhost", "token", "fingerprint")
    private fun remote() = JSONObject().put("chatId", "chat").put("agentId", "deepseek-harness")
        .put("workspacePath", "C:\\repo").put("workspaceId", "workspace").put("sessionId", "old").put("sessionResumable", true)
        .put("historyReplaySupported", false).put("status", "idle")
    private fun result() = JSONObject().put("type", "session.resume.result").put("chatId", "chat")
        .put("sessionId", "new").put("contextOnly", true).put("resumable", true).put("historyReplaySupported", false)
    private fun saved() = mergeSharedChat(machine, remote(), null).copy(
        messages = listOf(ChatMessage(MessageRole.User, "saved text", 1)),
        lastBridgeEventId = 15, bridgeEventGeneration = "generation", timelineId = "timeline",
        localHistoryBefore = 50, historyId = "old-history", historyHasMore = true, historyNextBefore = 10,
    )

    @Test fun contextRecoveryPreservesMessagesTimelineAndCheckpointButNotProviderHistoryPointer() {
        val before = saved()
        val after = before.withResumedContext(result().toContextResumeResult("chat", "new"), "context boundary")
        assertEquals("new", after.acpSessionId)
        assertEquals(before.messages, after.messages.dropLast(1))
        assertEquals("context boundary", after.messages.last().text)
        assertEquals(before.timelineId, after.timelineId)
        assertEquals(before.localHistoryBefore, after.localHistoryBefore)
        assertEquals(before.lastBridgeEventId, after.lastBridgeEventId)
        assertEquals(before.bridgeEventGeneration, after.bridgeEventGeneration)
        assertNull(after.historyId)
        assertNull(after.historyNextBefore)
        assertFalse(after.historyHasMore)
        assertFalse(after.historyReplaySupported)
    }

    @Test fun capabilitySurvivesStorageCatalogRefreshAndLegacyMetadata() {
        val before = saved()
        assertFalse(before.toJson().toChat().historyReplaySupported)
        val legacy = before.toJson().also { it.remove("historyReplaySupported") }
        assertTrue(legacy.toChat().historyReplaySupported)
        assertFalse(mergeSharedChat(machine, remote().also { it.remove("historyReplaySupported") }, before).historyReplaySupported)
    }

    @Test fun gapAcknowledgementPreservesSavedMessagesAndRequiresIdle() {
        val before = saved().copy(bridgeResyncRequired = true)
        val after = before.withUnrecoverableHistoryGap(23, "history is incomplete")
        assertFalse(after.bridgeResyncRequired)
        assertEquals(23, after.lastBridgeEventId)
        assertEquals(before.messages, after.messages.dropLast(1))
        assertEquals("history is incomplete", after.messages.last().text)
        assertEquals(before.timelineId, after.timelineId)
        assertThrows(IllegalArgumentException::class.java) {
            before.copy(agentStatus = "busy").withUnrecoverableHistoryGap(23, "warning")
        }
        assertThrows(IllegalArgumentException::class.java) {
            before.copy(historyReplaySupported = true).withUnrecoverableHistoryGap(23, "warning")
        }
    }

    @Test fun malformedOrWrongSessionAcknowledgementIsNotSuccessfulEmptyHistory() {
        for (field in listOf("sessionId", "contextOnly", "resumable", "historyReplaySupported")) {
            assertThrows(IOException::class.java) { result().also { it.remove(field) }.toContextResumeResult("chat", "new") }
        }
        assertThrows(IOException::class.java) { result().toContextResumeResult("chat", "wrong") }
        assertThrows(IOException::class.java) { result().put("error", "failed").toContextResumeResult("chat", "new") }
    }

    @Test fun bridgeUsesDedicatedResumeRequestAndParsesListCapability() = runBlocking {
        val server = MockWebServer()
        val frames = mutableListOf<JSONObject>()
        repeat(2) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val request = JSONObject(text)
                    frames.add(request)
                    webSocket.send(if (request.getString("type") == "session.list") {
                        """{"type":"session.list.result","sessions":[{"sessionId":"new","historyReplaySupported":false}]}"""
                    } else result().toString())
                    webSocket.send("""{"type":"bridge.done"}""")
                }
            }))
        }
        server.start()
        try {
            val target = machine.copy(endpoint = server.url("/").toString())
            val client = BridgeClient()
            withTimeout(5_000) {
                val sessions = client.listSessions(target, "deepseek-harness", "C:\\repo").getOrThrow()
                assertFalse(sessions.single().historyReplaySupported)
                val resumed = client.resumeSessionContext(target, "chat", "deepseek-harness", "C:\\repo", "new").result.getOrThrow()
                assertEquals("new", resumed.sessionId)
                assertFalse(resumed.historyReplaySupported)
            }
            assertEquals(listOf("session.list", "session.resume"), frames.map { it.getString("type") })
        } finally { server.shutdown() }
    }
}

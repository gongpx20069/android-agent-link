package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.model.QueuedPrompt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedPromptReplayTest {
    private val chat = Chat("chat", "Title", "machine", "Machine", "ws", "Workspace", "C:\\repo", "agent", "Agent", 1)
        .copy(queuedPrompts = listOf(
            QueuedPrompt("send", "Run tests", 1),
            QueuedPrompt("remove", "Do not run", 2, removing = true),
        ))

    @Test
    fun removalsPrecedeResubmission() {
        val sent = mutableListOf<JSONObject>()
        val connection = ChatConnection(chat.id) { sent.add(it); true }
        assertTrue(connection.restoreQueuedPrompts(chat))
        assertEquals(listOf("remove", "send"), sent.map { it.getString("operationId") })
        assertEquals("Run tests", sent.last().getString("content"))
    }

    @Test
    fun failedRemovalStopsReplayBeforeAnyPromptIsSent() {
        val sent = mutableListOf<JSONObject>()
        val connection = ChatConnection(chat.id) { sent.add(it); false }
        assertFalse(connection.restoreQueuedPrompts(chat))
        assertEquals(listOf("remove"), sent.map { it.getString("operationId") })
    }
}

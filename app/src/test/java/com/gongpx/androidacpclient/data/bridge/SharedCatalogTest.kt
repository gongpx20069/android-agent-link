package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.ChatMessage
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.model.MessageRole
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharedCatalogTest {
    private val machine = Machine("m", "Machine", "ws://localhost", "not-exported", "fingerprint")
    private fun remote() = JSONObject().put("chatId", "shared-cli-id").put("chatTitle", "CLI chat")
        .put("workspaceId", "w").put("workspacePath", "C:\\repo").put("agentId", "copilot")
        .put("sessionId", "shared-session").put("sessionResumable", true).put("status", "busy")

    @Test fun discoversExactSharedIdentityAndSession() {
        val chat = mergeSharedChat(machine, remote(), null)
        assertEquals("shared-cli-id", chat.id)
        assertEquals("shared-session", chat.acpSessionId)
        assertEquals("C:\\repo", chat.workspacePath)
        assertEquals("busy", chat.agentStatus)
        assertTrue(chat.acpSessionResumable)
    }

    @Test fun remoteBindingWinsWithoutErasingLocalEncryptedHistoryOrReplayCheckpoint() {
        val local = mergeSharedChat(machine, remote(), null).copy(
            acpSessionId = "old", title = "old title", lastBridgeEventId = 17,
            messages = listOf(ChatMessage(MessageRole.User, "retained", 1)),
        )
        val merged = mergeSharedChat(machine, remote(), local)
        assertEquals("shared-session", merged.acpSessionId)
        assertEquals("CLI chat", merged.title)
        assertSame(local.messages, merged.messages)
        assertEquals(17, merged.lastBridgeEventId)
    }

    @Test fun cannotRebindAnotherMachinesChat() {
        val local = mergeSharedChat(machine, remote(), null)
        assertThrows(IllegalArgumentException::class.java) {
            mergeSharedChat(machine.copy(id = "other"), remote(), local)
        }
    }
}

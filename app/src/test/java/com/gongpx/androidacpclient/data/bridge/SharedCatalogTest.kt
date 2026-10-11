package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.ChatMessage
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.model.MessageRole
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharedCatalogTest {
    @Test fun lateCatalogAndReplayCannotUndoConfirmedRename() {
        val initial = mergeSharedChat(machine, remote(), null)
        val latest = initial.applySharedTitle(remote().put("chatTitle", "New name")
            .put("titleRevision", 2).put("titleGeneration", "server"))
        val stale = remote().put("chatTitle", "Old name").put("titleRevision", 1).put("titleGeneration", "server")
        assertEquals("New name", latest.applySharedTitle(stale).title)
        assertEquals("New name", mergeSharedChat(machine, stale, latest).title)
        assertEquals("New name", latest.applySharedTitle(remote()).title)
        assertEquals("Next name", latest.applySharedTitle(remote().put("chatTitle", "Next name")
            .put("titleRevision", 3).put("titleGeneration", "server")).title)
        assertEquals("CLI chat", latest.applySharedTitle(remote().put("titleRevision", 0)
            .put("titleGeneration", "new-server")).title)
    }

    @Test fun titleChangesPreserveTranscriptAndRejectOtherChatIdentity() {
        val local = mergeSharedChat(machine, remote(), null).copy(
            lastBridgeEventId = 9, messages = listOf(ChatMessage(MessageRole.User, "Keep", 1)))
        val renamed = local.applySharedTitle(remote().put("chatTitle", "Renamed").put("titleRevision", 1)
            .put("titleGeneration", "server"))
        assertSame(local.messages, renamed.messages)
        assertEquals(9, renamed.lastBridgeEventId)
        assertThrows(IllegalArgumentException::class.java) {
            renamed.applySharedTitle(remote().put("chatId", "someone-else"))
        }
    }

    @Test fun tombstoneCannotBeMergedBackIntoAChat() {
        assertThrows(IllegalArgumentException::class.java) {
            mergeSharedChat(machine, remote().put("deleted", true), null)
        }
    }

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

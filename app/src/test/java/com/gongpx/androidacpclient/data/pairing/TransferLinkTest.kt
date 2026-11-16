package com.gongpx.androidacpclient.data.pairing

import com.gongpx.androidacpclient.data.model.*
import javax.crypto.AEADBadTagException
import org.junit.Assert.*
import org.junit.Test

class TransferLinkTest {
    private val password = "separate transfer password"
    private val machine = Machine("m", "Computer", "wss://test-4317.devtunnels.ms", "private-device-token",
        "sha256:" + "a".repeat(64), mapOf("X-Tunnel-Authorization" to "tunnel private-relay-token"))
    private val chat = Chat("c", "Current title", "m", "Computer", "w", "Project", "C:\\project", "copilot-cli", "Copilot", 1,
        acpSessionId = "saved-session", acpSessionResumable = true, titleRevision = 3, titleGeneration = "server",
        messages = listOf(ChatMessage(MessageRole.User, "do not export transcript", 1)),
        queuedPrompts = listOf(QueuedPrompt("op", "NEVER replay this queued action", 1)),
        lastBridgeEventId = 987, agentStatus = "busy")

    @Test fun selfContainedLinksNeedNoPasswordAndRetainAuthorizationWithoutPendingActions() {
        val bundle = TransferBundle(listOf(machine), listOf(chat))
        val link = TransferLink.encrypt(bundle)
        assertFalse(TransferLink.requiresPassword(link))
        val restored = TransferLink.decrypt(link)
        assertEquals(machine.deviceToken, restored.machines.single().deviceToken)
        assertEquals(machine.connectionHeaders, restored.machines.single().connectionHeaders)
        assertEquals(chat.acpSessionId, restored.chats.single().acpSessionId)
        assertTrue(restored.chats.single().messages.isEmpty())
        assertTrue(restored.chats.single().queuedPrompts.isEmpty())
        assertNotEquals(link, TransferLink.encrypt(bundle))
        assertThrows(AEADBadTagException::class.java) {
            val parts = link.removePrefix(TransferLink.PREFIX).split('.').toMutableList()
            parts[2] = (if (parts[2][0] == 'A') "B" else "A") + parts[2].substring(1)
            TransferLink.decrypt(TransferLink.PREFIX + parts.joinToString("."))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferLink.decrypt(link.replace("#2.", "#3."))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferLink.decrypt(TransferLink.PREFIX + "2." + "a".repeat(TransferLink.MAX_LINK_CHARS))
        }
    }

    @Test fun encryptedRoundTripTransfersAuthorizationAndReferencesButNoActionsOrHistory() {
        val bundle = TransferBundle(listOf(machine), listOf(chat))
        val link = TransferLink.encrypt(bundle, password)
        assertFalse(link.contains(machine.deviceToken))
        assertFalse(link.contains("Current title"))
        val restored = TransferLink.decrypt(link, password)
        assertEquals(machine.deviceToken, restored.machines.single().deviceToken)
        assertEquals(machine.connectionHeaders, restored.machines.single().connectionHeaders)
        assertNull(restored.machines.single().tunnelBinding)
        val recovered = restored.chats.single()
        assertEquals(chat.id, recovered.id)
        assertEquals("saved-session", recovered.acpSessionId)
        assertEquals(3L, recovered.titleRevision)
        assertTrue(recovered.queuedPrompts.isEmpty())
        assertTrue(recovered.messages.isEmpty())
        assertEquals(0, recovered.lastBridgeEventId)
        assertEquals("unknown", recovered.agentStatus)
        assertNotEquals(link, TransferLink.encrypt(bundle, password))
    }

    @Test fun wrongPasswordAndModifiedCiphertextNeverProduceAnImport() {
        val link = TransferLink.encrypt(TransferBundle(listOf(machine), emptyList()), password)
        assertThrows(AEADBadTagException::class.java) { TransferLink.decrypt(link, "incorrect password") }
        val parts = link.split('.').toMutableList()
        val data = parts.last()
        parts[parts.lastIndex] = (if (data[0] == 'A') "B" else "A") + data.substring(1)
        assertThrows(AEADBadTagException::class.java) { TransferLink.decrypt(parts.joinToString("."), password) }
        assertThrows(IllegalArgumentException::class.java) {
            TransferLink.decrypt(TransferLink.PREFIX + "a".repeat(TransferLink.MAX_LINK_CHARS), password)
        }
    }

    @Test fun rejectsUnsafeEndpointsHeadersAndMissingMachineReferences() {
        listOf("ws://public.example.com", "wss://user:password@host", "wss://host?token=leak",
            "https://host", "wss://host/path").forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) {
                TransferLink.encrypt(TransferBundle(listOf(machine.copy(endpoint = endpoint)), emptyList()), password)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferLink.encrypt(TransferBundle(listOf(machine.copy(connectionHeaders = mapOf("Authorization" to "secret"))), emptyList()), password)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferLink.encrypt(TransferBundle(listOf(machine), listOf(chat.copy(machineId = "other"))), password)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferLink.encrypt(TransferBundle(List(201) { machine }, emptyList()), password)
        }
    }

    @Test fun confirmedImportCannotReplaceDifferentMachineOrResurrectDeletedChat() {
        val bundle = TransferBundle(listOf(machine), listOf(chat))
        TransferLink.validateImport(bundle, listOf(machine.copy(deviceToken = "old")), listOf(chat)) { false }
        assertThrows(IllegalArgumentException::class.java) {
            TransferLink.validateImport(bundle, listOf(machine.copy(bridgeFingerprint = "different")), emptyList()) { false }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferLink.validateImport(bundle, emptyList(), listOf(chat.copy(machineId = "another"))) { false }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferLink.validateImport(bundle, emptyList(), emptyList()) { true }
        }
    }
}

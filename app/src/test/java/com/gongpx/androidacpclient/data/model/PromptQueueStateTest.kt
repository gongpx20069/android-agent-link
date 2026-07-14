package com.gongpx.androidacpclient.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptQueueStateTest {
    @Test
    fun queuedPromptMovesIntoTimelineWhenItStarts() {
        val chat = testChat().copy(
            queuedPrompts = listOf(QueuedPrompt("op_1", "run tests", 10)),
        )

        val started = chat.startQueuedPrompt("op_1", "ignored wire fallback", 20)

        assertEquals(emptyList<QueuedPrompt>(), started.queuedPrompts)
        assertEquals("run tests", started.messages.single().text)
        assertEquals("op_1", started.messages.single().operationId)
    }

    @Test
    fun replayedStartDoesNotDuplicateUserMessage() {
        val chat = testChat().copy(
            messages = listOf(ChatMessage(MessageRole.User, "run tests", 20, operationId = "op_1")),
        )

        val replayed = chat.startQueuedPrompt("op_1", "run tests", 30)

        assertEquals(1, replayed.messages.size)
    }

    @Test
    fun onlyQueueDrainIsFinalCompletion() {
        assertEquals(false, isFinalPromptCompletion("completed", 1))
        assertEquals(false, isFinalPromptCompletion("failed", 0))
        assertEquals(true, isFinalPromptCompletion("completed", 0))
    }

    private fun testChat(): Chat {
        return Chat(
            id = "chat_1",
            title = "Chat",
            machineId = "machine_1",
            machineName = "Machine",
            workspaceId = "workspace_1",
            workspaceName = "Workspace",
            workspacePath = "D:\\repo",
            agentId = "copilot-cli",
            agentName = "Copilot",
            createdAtMillis = 1,
        )
    }
}

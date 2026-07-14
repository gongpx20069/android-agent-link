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
    fun startingAcceptanceMovesCurrentPromptIntoTimeline() {
        val activity = ChatMessage(
            role = MessageRole.Agent,
            text = "Searching",
            timestampMillis = 15,
            kind = ChatMessageKind.Activity,
        )
        val chat = testChat().copy(
            messages = listOf(activity),
            queuedPrompts = listOf(QueuedPrompt("op_1", "run tests", 10)),
        )

        val accepted = chat.acceptPrompt("op_1", "starting", "run tests", 20)

        assertEquals(emptyList<QueuedPrompt>(), accepted.queuedPrompts)
        assertEquals(listOf(activity, accepted.messages.last()), accepted.messages)
        assertEquals("run tests", accepted.messages.last().text)
    }

    @Test
    fun legacyAcceptanceWithoutStateMovesPromptIntoTimeline() {
        val chat = testChat().copy(
            queuedPrompts = listOf(QueuedPrompt("op_1", "legacy prompt", 10)),
        )

        val accepted = chat.acceptPrompt("op_1", "", "", 20)

        assertEquals("legacy prompt", accepted.messages.single().text)
        assertEquals(emptyList<QueuedPrompt>(), accepted.queuedPrompts)
    }

    @Test
    fun completionPreservesPromptWhenStartedEventWasMissed() {
        val activity = ChatMessage(
            role = MessageRole.Agent,
            text = "Tool completed",
            timestampMillis = 15,
            kind = ChatMessageKind.Activity,
        )
        val chat = testChat().copy(
            messages = listOf(activity),
            queuedPrompts = listOf(QueuedPrompt("op_1", "keep me", 10)),
        )

        val completed = chat.finishPrompt("op_1", "completed", 20)

        assertEquals(2, completed.messages.size)
        assertEquals(activity, completed.messages.first())
        assertEquals("keep me", completed.messages.last().text)
        assertEquals(emptyList<QueuedPrompt>(), completed.queuedPrompts)
    }

    @Test
    fun onlyQueueDrainIsFinalCompletion() {
        assertEquals(false, isFinalPromptCompletion("completed", 1))
        assertEquals(false, isFinalPromptCompletion("failed", 0))
        assertEquals(true, isFinalPromptCompletion("completed", 0))
    }

    @Test
    fun idleIsIgnoredUntilTheActivePromptOperationCompletes() {
        assertEquals(false, shouldApplyChatStatus("idle", "op_1"))
        assertEquals(true, shouldApplyChatStatus("busy", "op_1"))
        assertEquals(true, shouldApplyChatStatus("idle", null))
    }

    @Test
    fun alreadyStartedRemovalResultIsNotTerminal() {
        assertEquals(false, isTerminalPromptStatus("already_started"))
        assertEquals(true, isTerminalPromptStatus("completed"))
        assertEquals(true, isTerminalPromptStatus("failed"))
        assertEquals(true, isTerminalPromptStatus("cancelled"))
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

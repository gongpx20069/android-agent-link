package com.gongpx.androidacpclient.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptQueueStateTest {
    @Test
    fun sessionBindingIsPersistedAndNeverDowngradedByReplay() {
        val bound = testChat().bindAcpSession("session-1", resumable = false)
        val resumable = bound.bindAcpSession("session-1", resumable = true)
        val replayed = resumable.bindAcpSession("session-1", resumable = false)

        assertEquals("session-1", replayed.acpSessionId)
        assertEquals(true, replayed.acpSessionResumable)
    }

    @Test
    fun replacementSessionUsesItsOwnResumableState() {
        val original = testChat().bindAcpSession("session-1", resumable = true)

        val replaced = original.bindAcpSession("session-2", resumable = false)

        assertEquals("session-2", replaced.acpSessionId)
        assertEquals(false, replaced.acpSessionResumable)
    }

    @Test
    fun bridgeEventCheckpointOnlyMovesForward() {
        val checkpointed = testChat().recordBridgeEventId(42)
        val replayedOlder = checkpointed.recordBridgeEventId(40)

        assertEquals(42, replayedOlder.lastBridgeEventId)
    }

    @Test
    fun bridgeEventCheckpointCanResetForNewBridgeGeneration() {
        assertEquals(0, testChat().recordBridgeEventId(42).resetBridgeEventCheckpoint().lastBridgeEventId)
    }

    @Test
    fun newBridgeEventGenerationResetsCheckpoint() {
        val rebound = testChat()
            .copy(bridgeEventGeneration = "old")
            .recordBridgeEventId(42)
            .bindBridgeEventGeneration("new", checkpointReset = true)

        assertEquals("new", rebound.bridgeEventGeneration)
        assertEquals(0, rebound.lastBridgeEventId)
    }

    @Test
    fun recentSessionRecoveryAppendsOnlyMessagesAfterOverlap() {
        val existing = listOf(
            ChatMessage(MessageRole.User, "one", 1),
            ChatMessage(MessageRole.Agent, "two", 2),
            ChatMessage(MessageRole.Agent, "tool", 3, kind = ChatMessageKind.Activity),
            ChatMessage(MessageRole.User, "three", 4),
        )
        val recent = listOf(
            ChatMessage(MessageRole.Agent, "two", 20),
            ChatMessage(MessageRole.User, "three", 30),
            ChatMessage(MessageRole.Agent, "four", 40),
        )

        val reconciled = reconcileRecentSessionMessages(existing, recent)

        assertEquals(listOf("one", "two", "tool", "three", "four"), reconciled.map { it.text })
    }

    @Test
    fun repeatedRecoveredMessageWithNewIdIsPreserved() {
        val existing = listOf(
            ChatMessage(MessageRole.User, "continue", 1, activityId = "message_old"),
        )
        val repeated = listOf(
            ChatMessage(MessageRole.User, "continue", 2, activityId = "message_new"),
        )

        assertEquals(2, reconcileRecentSessionMessages(existing, repeated).size)
    }

    @Test
    fun recoveredMessageWithSameIdIsNotDuplicated() {
        val existing = listOf(
            ChatMessage(MessageRole.Agent, "done", 1, activityId = "message_1"),
        )
        val recovered = listOf(
            ChatMessage(MessageRole.Agent, "done", 2, activityId = "message_1"),
        )

        assertEquals(existing, reconcileRecentSessionMessages(existing, recovered))
    }

    @Test
    fun recoveredMessageReplacesPartialTextWithSameId() {
        val existing = listOf(
            ChatMessage(MessageRole.Agent, "hel", 1, activityId = "message_1"),
        )
        val recovered = listOf(
            ChatMessage(MessageRole.Agent, "hello", 2, activityId = "message_1"),
        )

        assertEquals("hello", reconcileRecentSessionMessages(existing, recovered).single().text)
    }

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
    fun batchedPromptsMoveIntoTimelineInFifoOrder() {
        val chat = testChat().copy(
            queuedPrompts = listOf(
                QueuedPrompt("op_1", "first queued", 10),
                QueuedPrompt("op_2", "second queued", 11),
            ),
        )

        val started = chat
            .startQueuedPrompt("op_1", "first queued", 20)
            .startQueuedPrompt("op_2", "second queued", 21)

        assertEquals(emptyList<QueuedPrompt>(), started.queuedPrompts)
        assertEquals(listOf("first queued", "second queued"), started.messages.map { it.text })
        assertEquals(listOf("op_1", "op_2"), started.messages.map { it.operationId })
    }

    @Test
    fun queuedPromptRemovalIsHiddenWithDurableTombstone() {
        val chat = testChat().copy(
            queuedPrompts = listOf(
                QueuedPrompt("op_1", "remove me", 10),
                QueuedPrompt("op_2", "keep me", 11),
            ),
        )

        val removing = chat.markQueuedPromptRemoving("op_1")

        assertEquals(listOf("op_1", "op_2"), removing.queuedPrompts.map { it.operationId })
        assertEquals(true, removing.queuedPrompts.first().removing)
        assertEquals(false, removing.queuedPrompts.last().removing)
    }

    @Test
    fun cancelledLifecycleRemovesDurableTombstone() {
        val chat = testChat().copy(
            queuedPrompts = listOf(QueuedPrompt("op_1", "remove me", 10, removing = true)),
        )

        val cancelled = chat.finishPrompt("op_1", "cancelled", 20)

        assertEquals(emptyList<QueuedPrompt>(), cancelled.queuedPrompts)
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

    @Test
    fun alreadyStartedRemovalMovesHiddenPromptIntoTimeline() {
        val chat = testChat().copy(
            queuedPrompts = listOf(QueuedPrompt("op_1", "already running", 10, removing = true)),
        )

        val started = chat.finishPrompt("op_1", "already_started", 20)

        assertEquals(emptyList<QueuedPrompt>(), started.queuedPrompts)
        assertEquals("already running", started.messages.single().text)
        assertEquals("op_1", started.messages.single().operationId)
    }

    @Test
    fun cancellationClearsBusyOnlyForActivePromptWithoutMoreWork() {
        assertEquals(true, shouldClearBusyAfterCancellation(true, emptyList()))
        assertEquals(false, shouldClearBusyAfterCancellation(false, emptyList()))
        assertEquals(
            false,
            shouldClearBusyAfterCancellation(
                true,
                listOf(QueuedPrompt("op_2", "next", 10)),
            ),
        )
        assertEquals(
            true,
            shouldClearBusyAfterCancellation(
                true,
                listOf(QueuedPrompt("op_2", "also removing", 10, removing = true)),
            ),
        )
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

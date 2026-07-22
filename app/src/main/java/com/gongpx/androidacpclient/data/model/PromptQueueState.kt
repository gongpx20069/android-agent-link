package com.gongpx.androidacpclient.data.model

fun Chat.bindAcpSession(sessionId: String, resumable: Boolean): Chat {
    if (sessionId.isBlank()) return this
    val nextResumable = if (sessionId == acpSessionId) acpSessionResumable || resumable else resumable
    return copy(acpSessionId = sessionId, acpSessionResumable = nextResumable)
}

fun Chat.recordBridgeEventId(eventId: Int): Chat {
    return if (eventId > lastBridgeEventId) copy(lastBridgeEventId = eventId) else this
}

fun Chat.resetBridgeEventCheckpoint(): Chat {
    return if (lastBridgeEventId == 0) this else copy(lastBridgeEventId = 0)
}

fun Chat.bindBridgeEventGeneration(generation: String, checkpointReset: Boolean): Chat {
    val shouldReset = checkpointReset || bridgeEventGeneration != generation
    return copy(
        bridgeEventGeneration = generation,
        lastBridgeEventId = if (shouldReset) 0 else lastBridgeEventId,
    )
}

fun reconcileRecentSessionMessages(
    existing: List<ChatMessage>,
    recent: List<ChatMessage>,
): List<ChatMessage> {
    val recentConversation = recent.filter { it.isConversationMessage() }
    if (recentConversation.isEmpty()) return existing
    val reconciledExisting = existing.toMutableList()
    recentConversation.forEach { recovered ->
        val recoveredId = recovered.stableMessageId() ?: return@forEach
        val existingIndex = reconciledExisting.indexOfLast { message ->
            message.isConversationMessage() && message.stableMessageId() == recoveredId
        }
        if (existingIndex >= 0) {
            reconciledExisting[existingIndex] = reconciledExisting[existingIndex].copy(
                role = recovered.role,
                text = recovered.text,
                activityId = recovered.activityId,
            )
        }
    }
    val existingConversation = reconciledExisting.filter { it.isConversationMessage() }
    val maxOverlap = minOf(existingConversation.size, recentConversation.size)
    val overlap = if (recentConversation.size == 1) {
        val existingId = existingConversation.lastOrNull()?.stableMessageId()
        val recentId = recentConversation.single().stableMessageId()
        if (existingId != null && existingId == recentId) 1 else 0
    } else {
        (maxOverlap downTo 0).first { length ->
            existingConversation.takeLast(length)
                .zip(recentConversation.take(length))
                .all { (left, right) -> left.sameRecoveredMessage(right) }
        }
    }
    return reconciledExisting + recentConversation.drop(overlap)
}

private fun ChatMessage.isConversationMessage(): Boolean {
    return kind == ChatMessageKind.Message && (role == MessageRole.User || role == MessageRole.Agent)
}

private fun ChatMessage.sameRecoveredMessage(other: ChatMessage): Boolean {
    if (role != other.role) return false
    val leftId = stableMessageId()
    val rightId = other.stableMessageId()
    return if (leftId != null && rightId != null) {
        leftId == rightId
    } else {
        text == other.text
    }
}

private fun ChatMessage.stableMessageId(): String? {
    return activityId?.takeUnless { it == "user_message" || it == "agent_message" }
}

fun Chat.startQueuedPrompt(operationId: String, content: String, nowMillis: Long): Chat {
    val queued = queuedPrompts.firstOrNull { it.operationId == operationId }
    val text = queued?.text ?: content
    val remaining = queuedPrompts.filterNot { it.operationId == operationId }
    if (text.isBlank() || messages.any { it.operationId == operationId }) {
        return copy(queuedPrompts = remaining)
    }
    return copy(
        messages = messages + ChatMessage(
            role = MessageRole.User,
            text = text,
            timestampMillis = nowMillis,
            operationId = operationId,
        ),
        queuedPrompts = remaining,
    )
}

fun Chat.removeQueuedPrompt(operationId: String): Chat {
    return copy(queuedPrompts = queuedPrompts.filterNot { it.operationId == operationId })
}

fun Chat.markQueuedPromptRemoving(operationId: String): Chat {
    return copy(
        queuedPrompts = queuedPrompts.map { queued ->
            if (queued.operationId == operationId) queued.copy(removing = true) else queued
        },
    )
}

fun Chat.acceptPrompt(
    operationId: String,
    state: String,
    content: String,
    nowMillis: Long,
): Chat {
    return when (state) {
        "queued" -> {
            if (
                content.isBlank() ||
                messages.any { it.operationId == operationId } ||
                queuedPrompts.any { it.operationId == operationId }
            ) {
                this
            } else {
                copy(
                    queuedPrompts = queuedPrompts + QueuedPrompt(
                        operationId = operationId,
                        text = content,
                        createdAtMillis = nowMillis,
                    ),
                )
            }
        }
        "cancelled" -> removeQueuedPrompt(operationId)
        "completed", "failed", "running", "starting", "" ->
            startQueuedPrompt(operationId, content, nowMillis)
        else -> this
    }
}

fun Chat.finishPrompt(operationId: String, status: String, nowMillis: Long): Chat {
    return when (status) {
        "completed", "failed", "already_started" -> startQueuedPrompt(operationId, "", nowMillis)
        "cancelled" -> removeQueuedPrompt(operationId)
        else -> this
    }
}

fun isFinalPromptCompletion(status: String, queueRemaining: Int): Boolean {
    return status == "completed" && queueRemaining == 0
}

fun shouldApplyChatStatus(status: String, activeOperationId: String?): Boolean {
    return status != "idle" || activeOperationId == null
}

fun isTerminalPromptStatus(status: String): Boolean {
    return status in setOf("completed", "failed", "cancelled")
}

fun shouldClearBusyAfterCancellation(wasActivePrompt: Boolean, queuedPrompts: List<QueuedPrompt>): Boolean {
    return wasActivePrompt && queuedPrompts.none { !it.removing }
}

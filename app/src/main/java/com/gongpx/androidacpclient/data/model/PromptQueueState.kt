package com.gongpx.androidacpclient.data.model

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
        "completed", "failed" -> startQueuedPrompt(operationId, "", nowMillis)
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

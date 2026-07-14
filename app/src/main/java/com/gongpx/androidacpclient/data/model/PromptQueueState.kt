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

fun isFinalPromptCompletion(status: String, queueRemaining: Int): Boolean {
    return status == "completed" && queueRemaining == 0
}

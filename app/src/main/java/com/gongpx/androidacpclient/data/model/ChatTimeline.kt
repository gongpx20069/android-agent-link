package com.gongpx.androidacpclient.data.model

fun List<ChatMessage>.mergeTimelineMessage(message: ChatMessage): List<ChatMessage> {
    val streamId = message.activityId ?: return this + message
    val existingIndex = indexOfLast {
        it.activityId == streamId && it.kind == message.kind &&
            (message.kind == ChatMessageKind.CommandUpdate || message.kind == ChatMessageKind.ConfigUpdate ||
                message.operationId == null || it.operationId == message.operationId)
    }
    if (existingIndex < 0) return this + message
    if (message.kind == ChatMessageKind.Message && existingIndex != lastIndex) return this + message
    return toMutableList().also { current ->
        val existing = current[existingIndex]
        current[existingIndex] = when {
            message.isToolSnapshot -> message.copy(localId = existing.localId)
            message.kind == ChatMessageKind.Activity && existing.kind == ChatMessageKind.Activity ->
                if (existing.title == "Thought" && message.title == "Thought") {
                    existing.copy(details = listOfNotNull(existing.details, message.details).joinToString(""))
                } else mergeToolActivity(existing, message).copy(localId = existing.localId)
            message.kind == ChatMessageKind.Message && existing.kind == ChatMessageKind.Message ->
                existing.copy(text = existing.text + message.text)
            else -> message.copy(localId = existing.localId)
        }
    }
}

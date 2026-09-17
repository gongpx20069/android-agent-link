package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.ChatMessage
import com.gongpx.androidacpclient.data.model.ChatMessageKind
import com.gongpx.androidacpclient.data.model.mergeToolActivity

/** Runs on the socket reader, so large JSON tool deltas never merge inside composition callbacks. */
internal class ToolStreamReducer(initialMessages: List<ChatMessage>) {
    private var initial = initialMessages.filter { it.kind == ChatMessageKind.Activity && it.title != "Thought" }
    private val tools = mutableMapOf<Pair<String, String?>, ChatMessage>()

    fun reduce(message: ChatMessage): ChatMessage {
        val activity = message.activityId
        if (message.kind != ChatMessageKind.Activity || message.title == "Thought" || activity == null) return message
        val key = activity to message.operationId
        val previous = tools[key] ?: initial.lastOrNull {
            it.kind == ChatMessageKind.Activity && it.activityId == activity &&
                (message.operationId == null || it.operationId == message.operationId)
        }
        val next = (previous?.let { mergeToolActivity(it, message) } ?: message).copy(isToolSnapshot = true)
        tools[key] = next
        return next
    }

    fun finishTurn(operationId: String) {
        tools.keys.removeAll { it.second == operationId }
        initial = initial.filterNot { it.operationId == operationId }
    }
}

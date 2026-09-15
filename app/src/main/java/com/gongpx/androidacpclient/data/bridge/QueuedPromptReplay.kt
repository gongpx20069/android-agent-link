package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.Chat

fun ChatConnection.restoreQueuedPrompts(chat: Chat): Boolean {
    val queue = chat.queuedPrompts
    return (queue.filter { it.removing } + queue.filterNot { it.removing }).all { prompt ->
        if (prompt.removing) removeQueuedPrompt(prompt.operationId) else sendPrompt(
            prompt.operationId, chat.agentId, chat.workspacePath, prompt.text,
            chat.acpSessionId, chat.acpSessionResumable,
        )
    }
}

package com.gongpx.androidacpclient.data.notification

internal data class ChatCompletionAttention(
    val markUnread: Boolean,
    val showNotification: Boolean,
)

internal fun chatCompletionAttention(appInForeground: Boolean, chatIsOpen: Boolean): ChatCompletionAttention {
    return ChatCompletionAttention(
        markUnread = !appInForeground || !chatIsOpen,
        showNotification = !appInForeground,
    )
}

internal fun chatCompletionPreview(
    latestAgentPreview: String?,
    persistedAgentMessages: List<String>,
    fallback: String,
): String {
    return latestAgentPreview
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: persistedAgentMessages.lastOrNull { it.isNotBlank() }?.trim()
        ?: fallback
}

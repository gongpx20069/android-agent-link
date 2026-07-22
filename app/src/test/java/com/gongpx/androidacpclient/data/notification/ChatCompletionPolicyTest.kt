package com.gongpx.androidacpclient.data.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCompletionPolicyTest {
    @Test
    fun foregroundOpenChatNeedsNoAttention() {
        assertEquals(
            ChatCompletionAttention(markUnread = false, showNotification = false),
            chatCompletionAttention(appInForeground = true, chatIsOpen = true),
        )
    }

    @Test
    fun foregroundDifferentScreenMarksUnreadWithoutNotification() {
        assertEquals(
            ChatCompletionAttention(markUnread = true, showNotification = false),
            chatCompletionAttention(appInForeground = true, chatIsOpen = false),
        )
    }

    @Test
    fun backgroundMarksUnreadAndShowsNotification() {
        assertEquals(
            ChatCompletionAttention(markUnread = true, showNotification = true),
            chatCompletionAttention(appInForeground = false, chatIsOpen = true),
        )
    }

    @Test
    fun completionPreviewUsesLatestStreamedMessage() {
        assertEquals(
            "latest response",
            chatCompletionPreview(
                latestAgentPreview = " latest response ",
                persistedAgentMessages = listOf("older response"),
                fallback = "completed",
            ),
        )
    }

    @Test
    fun completionPreviewFallsBackToPersistedAgentMessageAfterRestart() {
        assertEquals(
            "persisted response",
            chatCompletionPreview(
                latestAgentPreview = null,
                persistedAgentMessages = listOf("older response", " persisted response "),
                fallback = "completed",
            ),
        )
    }
}

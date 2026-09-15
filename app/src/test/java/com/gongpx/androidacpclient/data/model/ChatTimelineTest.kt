package com.gongpx.androidacpclient.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTimelineTest {
    private fun message(text: String, operationId: String = "one") =
        ChatMessage(MessageRole.Agent, text, 1, activityId = "stream", operationId = operationId)

    @Test
    fun consecutiveChunksOfSameTurnAreMerged() {
        val timeline = listOf(message("Hello")).mergeTimelineMessage(message(" world"))
        assertEquals("Hello world", timeline.single().text)
    }

    @Test
    fun reusedStreamIdInAnotherTurnDoesNotMerge() {
        val timeline = listOf(message("First")).mergeTimelineMessage(message("Second", "two"))
        assertEquals(listOf("First", "Second"), timeline.map { it.text })
    }

    @Test
    fun interveningActivityKeepsItsPositionBetweenTextChunks() {
        val activity = message("Working").copy(kind = ChatMessageKind.Activity, activityId = "tool")
        val timeline = listOf(message("Before"), activity).mergeTimelineMessage(message("After"))
        assertEquals(listOf("Before", "Working", "After"), timeline.map { it.text })
    }

    @Test
    fun reusedToolIdInAnotherTurnDoesNotOverwriteEarlierActivity() {
        val first = message("First tool").copy(kind = ChatMessageKind.Activity, activityId = "tool")
        val second = first.copy(text = "Second tool", operationId = "two")
        assertEquals(listOf(first, second), listOf(first).mergeTimelineMessage(second))
    }
}

package com.gongpx.androidacpclient.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class QueuedPromptPreviewTest {
    @Test fun preservesShortMessagesAndCapsLongMessagesAtEightyCharacters() {
        assertEquals("", queuedPromptPreview(""))
        assertEquals("Short prompt", queuedPromptPreview("Short prompt"))
        assertEquals("a".repeat(80), queuedPromptPreview("a".repeat(80)))
        assertEquals("a".repeat(80) + "...", queuedPromptPreview("a".repeat(81)))
    }

    @Test fun replacesLineBreaksAndTabsSoPreviewCannotGrowVertically() {
        assertEquals("one two three four", queuedPromptPreview("one\ntwo\tthree\rfour"))
    }

    @Test fun preservesSupplementaryUnicodeCharactersAtTheBoundary() {
        val emoji = "\uD83D\uDE00"
        assertEquals("a".repeat(79) + emoji + "...", queuedPromptPreview("a".repeat(79) + emoji + "tail"))
        assertEquals(emoji.repeat(80) + "...", queuedPromptPreview(emoji.repeat(81)))
    }
}

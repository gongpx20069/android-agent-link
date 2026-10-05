package com.gongpx.androidacpclient.data.store

import com.gongpx.androidacpclient.data.bridge.ChatConnection
import com.gongpx.androidacpclient.data.bridge.restoreQueuedPrompts
import com.gongpx.androidacpclient.data.model.*
import com.gongpx.androidacpclient.data.store.ChatJsonCodec.toChat
import com.gongpx.androidacpclient.data.store.ChatJsonCodec.toJson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImageAttachmentTest {
    private val image = ImageAttachment("a".repeat(64), "image/png", 128)
    private val chat = Chat("chat", "Title", "m", "Machine", "w", "Workspace", "C:\\repo", "agent", "Agent", 1)

    @Test fun imageOnlyPromptSurvivesCodecReplayAndAcceptanceExactlyOnce() {
        val pending = chat.copy(queuedPrompts = listOf(QueuedPrompt("op", "", 1, image = image)))
        val restored = pending.toJson().toChat()
        assertEquals(image, restored.queuedPrompts.single().image)
        val frames = mutableListOf<JSONObject>()
        assertTrue(ChatConnection("chat") { frames.add(it); true }.restoreQueuedPrompts(restored))
        assertEquals(image, ImageAttachment.fromJson(frames.single().getJSONObject("image")))
        assertFalse(frames.single().toString().contains("\"data\""))
        val started = restored.startQueuedPrompt("op", "", 2)
        assertEquals(image, started.messages.single().image)
        assertTrue(started.queuedPrompts.isEmpty())
        assertEquals(image, started.copy(messages = emptyList()).toJson().toChat().activePromptImages["op"])
        assertTrue(started.finishPrompt("op", "failed", 3).activePromptImages.isEmpty())
        val duplicate = started.withPromptImage("op", "", image).acceptPrompt("op", "completed", "", 3)
        assertEquals(1, duplicate.messages.size)
        assertTrue(duplicate.activePromptImages.isEmpty())
        assertEquals(image, duplicate.toJson().toChat().messages.single().image)
    }

    @Test fun replayOnAnotherPhoneAndProviderEchoKeepOriginalImage() {
        val replayed = chat.withPromptImage("remote", "", image).acceptPrompt("remote", "queued", "", 2)
            .withPromptImage("remote", "", image).startQueuedPrompt("remote", "", 3)
        val merged = replayed.messages.mergeTimelineMessage(
            ChatMessage(MessageRole.User, "[Image replay]", 4, operationId = "remote", activityId = "provider-id"))
        assertEquals(1, merged.size)
        assertEquals(image, merged.single().image)
        assertEquals("", merged.single().text)
        assertEquals("provider-id", merged.single().activityId)
        val recovered = reconcileRecentSessionMessages(merged, listOf(
            ChatMessage(MessageRole.User, "", 5, activityId = "provider-id")))
        assertEquals(image, recovered.single().image)
        val page = JSONObject().put("messages", org.json.JSONArray().put(
            JSONObject().put("role", "user").put("text", "").put("kind", "text").put("image", image.toJson()),
        )).toHistoryPage()
        assertEquals(image, page.messages.single().image)
    }

    @Test fun strictMetadataRejectsPathsWrongFormatsAndCoercedSizes() {
        assertThrows(IllegalArgumentException::class.java) { ImageAttachment("../private", "image/png", 1) }
        assertThrows(IllegalArgumentException::class.java) { image.copy(mimeType = "image/svg+xml") }
        assertThrows(IllegalArgumentException::class.java) { image.copy(size = ImageAttachment.MAX_BYTES + 1) }
        for (size in listOf<Any>("128", 128.0, true)) {
            assertThrows(IllegalArgumentException::class.java) { ImageAttachment.fromJson(image.toJson().put("size", size)) }
        }
        assertThrows(IllegalArgumentException::class.java) { ImageAttachment.fromJson(image.toJson().put("data", "base64")) }
    }
}

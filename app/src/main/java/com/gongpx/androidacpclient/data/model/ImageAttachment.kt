package com.gongpx.androidacpclient.data.model

import org.json.JSONObject

data class ImageAttachment(val id: String, val mimeType: String, val size: Int) {
    init {
        require(id.matches(Regex("[0-9a-f]{64}"))) { "Invalid image identifier" }
        require(mimeType in setOf("image/png", "image/jpeg")) { "Unsupported image format" }
        require(size in 1..MAX_BYTES) { "Image must be no larger than 5 MiB" }
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("mimeType", mimeType).put("size", size)

    companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        fun fromJson(json: JSONObject): ImageAttachment {
            val size = json.get("size")
            require(json.length() == 3 && size is Int) { "Invalid image metadata" }
            return ImageAttachment(json.getString("id"), json.getString("mimeType"), size)
        }
    }
}

fun Chat.withPromptImage(operationId: String, text: String, image: ImageAttachment): Chat {
    if (messages.any { it.operationId == operationId && it.role == MessageRole.User }) {
        return copy(messages = messages.map {
            if (it.operationId == operationId && it.role == MessageRole.User) it.copy(image = image) else it
        })
    }
    return copy(queuedPrompts = if (queuedPrompts.any { it.operationId == operationId }) {
        queuedPrompts.map { if (it.operationId == operationId) it.copy(image = image) else it }
    } else queuedPrompts + QueuedPrompt(operationId, text, System.currentTimeMillis(), image = image))
}

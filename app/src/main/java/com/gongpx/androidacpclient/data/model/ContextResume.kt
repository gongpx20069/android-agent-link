package com.gongpx.androidacpclient.data.model

import java.io.IOException
import org.json.JSONObject

data class ContextResumeResult(val sessionId: String, val historyReplaySupported: Boolean)

fun JSONObject.toContextResumeResult(chatId: String, sessionId: String): ContextResumeResult {
    if (has("error") || optString("chatId") != chatId || optString("sessionId") != sessionId ||
        opt("contextOnly") != true || opt("resumable") != true || opt("historyReplaySupported") !is Boolean
    ) throw IOException("The bridge did not confirm context-only session recovery.")
    return ContextResumeResult(sessionId, getBoolean("historyReplaySupported"))
}

fun Chat.withResumedContext(result: ContextResumeResult, notice: String): Chat =
    bindAcpSession(result.sessionId, true).copy(
        historyReplaySupported = result.historyReplaySupported,
        historyId = null,
        historyNextBefore = null,
        historyHasMore = false,
        historyTotalMessages = 0,
        bridgeResyncRequired = false,
        messages = messages + ChatMessage(MessageRole.System, notice, System.currentTimeMillis()),
    )

fun Chat.withUnrecoverableHistoryGap(checkpoint: Int?, notice: String): Chat {
    require(!historyReplaySupported && agentStatus == "idle")
    return copy(
        bridgeResyncRequired = false,
        lastBridgeEventId = maxOf(lastBridgeEventId, checkpoint ?: 0),
        messages = messages + ChatMessage(MessageRole.System, notice, System.currentTimeMillis()),
    )
}

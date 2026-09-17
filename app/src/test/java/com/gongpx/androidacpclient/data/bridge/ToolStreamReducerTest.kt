package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolStreamReducerTest {
    private fun tool(operation: String, details: String): ChatMessage {
        val event = JSONObject(details)
        event.put("sessionUpdate", if (event.has("status")) "tool_call_update" else "tool_call")
        return requireNotNull(event.toToolActivity(1, operation))
    }

    @Test fun backgroundSnapshotRetainsPreviousOutputAndStableUiIdentity() {
        val initial = tool("active", """{"toolCallId":"tool","title":"Read","content":[{"type":"content","content":{"type":"text","text":"retained"}}]}""")
        val reducer = ToolStreamReducer(listOf(initial))
        val update = reducer.reduce(tool("active", """{"toolCallId":"tool","status":"completed"}"""))
        assertTrue(update.isToolSnapshot)
        assertTrue(update.details.orEmpty().contains("retained"))
        val timeline = listOf(initial).mergeTimelineMessage(update)
        assertEquals(initial.localId, timeline.single().localId)
        assertEquals("completed", JSONObject(timeline.single().details!!).getString("status"))
    }

    @Test fun replayCompletionOfAnotherTurnDoesNotClearActiveSeed() {
        val initial = tool("active", """{"toolCallId":"tool","title":"Read","rawOutput":"retained"}""")
        val reducer = ToolStreamReducer(listOf(initial))
        reducer.finishTurn("older")
        assertTrue(reducer.reduce(tool("active", """{"toolCallId":"tool","status":"completed"}""")).details.orEmpty().contains("retained"))
        reducer.finishTurn("active")
        assertFalse(reducer.reduce(tool("new", """{"toolCallId":"tool","status":"started"}""")).details.orEmpty().contains("retained"))
    }
}

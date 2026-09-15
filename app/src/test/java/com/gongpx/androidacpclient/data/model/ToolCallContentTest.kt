package com.gongpx.androidacpclient.data.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallContentTest {
    @Test
    fun structuredSectionsShowInputOutputDiffLocationsAndTerminalWithoutRawOutputEcho() {
        val sections = toolActivitySections(
            """
            {"sessionUpdate":"tool_call","rawInput":{"command":"test"},"rawOutput":"Tests passed",
             "content":[
               {"type":"content","content":{"type":"text","text":"Tests passed"}},
               {"type":"diff","path":"main.kt","oldText":"old","newText":"new"},
               {"type":"terminal","terminalId":"terminal-1"}
             ],"locations":[{"path":"main.kt","line":12},{"path":"other.kt"}],"terminalId":"terminal-1"}
            """.trimIndent(),
        )
        assertEquals(listOf("Input", "Output", "Diff", "Terminal", "Locations"), sections.map { it.first })
        assertEquals("test", JSONObject(sections[0].second).getString("command"))
        assertEquals("Tests passed", sections[1].second)
        assertTrue(sections[2].second.contains("main.kt"))
        assertTrue(sections[2].second.contains("--- Old\nold"))
        assertTrue(sections[2].second.contains("+++ New\nnew"))
        assertEquals("terminal-1", sections[3].second)
        assertEquals("main.kt:12\nother.kt", sections[4].second)
        assertFalse(sections.any { it.second.contains("duplicate raw output") })
    }

    @Test
    fun rawOutputWithAdditionalInformationIsNotHiddenByStructuredContent() {
        val sections = toolActivitySections("""{"sessionUpdate":"tool_call",
            "content":[{"type":"text","text":"Done"}],"rawOutput":{"exitCode":1,"stderr":"Details"}}""")
        assertEquals(2, sections.size)
        assertEquals("Done", sections.first().second)
        assertEquals("Details", JSONObject(sections.last().second).getString("stderr"))
    }

    @Test
    fun structuredSectionsUseRawOutputWhenContentIsAbsentAndRetainNonToolFallback() {
        val output = toolActivitySections(
            """{"sessionUpdate":"tool_call_update","rawOutput":{"exitCode":0},"content":[]}""",
        ).single()
        assertEquals("Output", output.first)
        assertEquals(0, JSONObject(output.second).getInt("exitCode"))
        listOf(null, "plain text", "[]", """{"sessionUpdate":"unknown","content":[]}""").forEach {
            assertTrue(toolActivitySections(it).isEmpty())
        }
        val unknownContent = toolActivitySections(
            """{"sessionUpdate":"tool_call","content":[{"type":"future_type","value":"preserved"}]}""",
        ).single()
        assertEquals("Content", unknownContent.first)
        assertEquals("preserved", JSONObject(unknownContent.second).getString("value"))
    }

    @Test
    fun nonToolOrMalformedActivityDetailsUseReplacementWithoutThrowing() {
        val existing = ChatMessage(
            role = MessageRole.Agent,
            text = "Old activity",
            timestampMillis = 1,
            kind = ChatMessageKind.Activity,
            details = "not json",
            activityId = "activity",
        )
        listOf(null, "Thinking about the task", "[]", """{"sessionUpdate":"custom_update"}""").forEach { details ->
            val incoming = existing.copy(text = "New activity", details = details, timestampMillis = 2)
            assertEquals(incoming, mergeToolActivity(existing, incoming))
        }
        val tool = requireNotNull(
            JSONObject("""{"sessionUpdate":"tool_call_update","toolCallId":"activity","status":"completed"}""")
                .toToolActivity(3),
        )
        assertEquals(tool, mergeToolActivity(existing, tool))
    }

    @Test
    fun preservesArrayContentIncludingDiffsAndRawData() {
        val update = JSONObject(
            """
            {"sessionUpdate":"tool_call","toolCallId":"tool-1","title":"Edit file","kind":"edit",
             "status":"in_progress","content":[
               {"type":"content","content":{"type":"text","text":"Editing"}},
               {"type":"diff","path":"src/Main.kt","oldText":"old","newText":"new"}
             ],"locations":[{"path":"src/Main.kt","line":12}],
             "rawInput":{"path":"src/Main.kt"},"rawOutput":{"ok":true}}
            """.trimIndent(),
        )
        val message = requireNotNull(update.toToolActivity(123, "operation-1"))
        val details = requireNotNull(parseToolCallDetails(message.details))
        assertEquals(2, details.getJSONArray("content").length())
        assertEquals("diff", details.getJSONArray("content").getJSONObject(1).getString("type"))
        assertEquals("new", details.getJSONArray("content").getJSONObject(1).getString("newText"))
        assertEquals("src/Main.kt", details.getJSONObject("rawInput").getString("path"))
        assertTrue(details.getJSONObject("rawOutput").getBoolean("ok"))
        assertEquals(12, details.getJSONArray("locations").getJSONObject(0).getInt("line"))
        assertEquals(123L, message.timestampMillis)
        assertEquals("operation-1", message.operationId)
    }

    @Test
    fun statusOnlyDeltaDoesNotManufactureOptionalFields() {
        val delta = JSONObject(
            """{"sessionUpdate":"tool_call_update","toolCallId":"tool-1","status":"completed"}""",
        )
        val incoming = requireNotNull(delta.toToolActivity(124))
        val details = requireNotNull(parseToolCallDetails(incoming.details))
        assertEquals(setOf("sessionUpdate", "toolCallId", "status"), details.keys().asSequence().toSet())
        assertFalse(details.has("content"))
        assertFalse(details.has("rawInput"))
        assertFalse(details.has("rawOutput"))
        assertFalse(details.has("title"))
        assertFalse(details.has("kind"))
    }

    @Test
    fun statusOnlyMergePreservesTitleKindContentLocationsAndRawData() {
        val existing = requireNotNull(
            JSONObject(
                """
                {"sessionUpdate":"tool_call","toolCallId":"tool-1","title":"Run tests",
                 "kind":"execute","status":"in_progress",
                 "content":[{"type":"content","content":{"type":"text","text":"test output"}}],
                 "rawInput":{"command":"test"},"rawOutput":[1,2],
                 "locations":[{"path":"test.kt"}]}
                """.trimIndent(),
            ).toToolActivity(1, "op-1"),
        )
        val incoming = requireNotNull(
            JSONObject("""{"sessionUpdate":"tool_call_update","toolCallId":"tool-1","status":"completed"}""")
                .toToolActivity(2),
        )
        val merged = mergeToolActivity(existing, incoming)
        val details = requireNotNull(parseToolCallDetails(merged.details))
        assertEquals("Run tests", merged.title)
        assertEquals("Run tests · completed", merged.text)
        assertEquals("execute", details.getString("kind"))
        assertEquals(1, details.getJSONArray("content").length())
        assertEquals("test", details.getJSONObject("rawInput").getString("command"))
        assertEquals("[1,2]", details.getJSONArray("rawOutput").toString())
        assertEquals(1, details.getJSONArray("locations").length())
        assertEquals("op-1", merged.operationId)
        assertFalse(requireNotNull(parseToolCallDetails(incoming.details)).has("content"))
    }

    @Test
    fun presentContentReplacesRatherThanAppendsAndEmptyClears() {
        val original = requireNotNull(
            JSONObject(
                """{"sessionUpdate":"tool_call","toolCallId":"t","content":[{"type":"diff","newText":"one"}]}""",
            ).toToolActivity(1),
        )
        val replacement = requireNotNull(
            JSONObject(
                """{"sessionUpdate":"tool_call_update","toolCallId":"t","content":[{"type":"diff","newText":"two"}]}""",
            ).toToolActivity(2),
        )
        val replaced = mergeToolActivity(original, replacement)
        val content = requireNotNull(parseToolCallDetails(replaced.details)).getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("two", content.getJSONObject(0).getString("newText"))
        val clear = requireNotNull(
            JSONObject("""{"sessionUpdate":"tool_call_update","toolCallId":"t","content":[]}""")
                .toToolActivity(3),
        )
        assertEquals(0, requireNotNull(parseToolCallDetails(mergeToolActivity(replaced, clear).details))
            .getJSONArray("content").length())
    }

    @Test
    fun explicitNullAndPrimitiveRawOutputArePreserved() {
        val original = requireNotNull(
            JSONObject("""{"sessionUpdate":"tool_call","toolCallId":"t","rawInput":"command","rawOutput":"old"}""")
                .toToolActivity(1),
        )
        val incoming = requireNotNull(
            JSONObject("""{"sessionUpdate":"tool_call_update","toolCallId":"t","rawOutput":null}""")
                .toToolActivity(2),
        )
        val details = requireNotNull(parseToolCallDetails(mergeToolActivity(original, incoming).details))
        assertEquals("command", details.getString("rawInput"))
        assertTrue(details.has("rawOutput"))
        assertTrue(details.isNull("rawOutput"))
        assertNull(JSONObject("""{"sessionUpdate":"agent_message_chunk"}""").toToolActivity(1))
    }
}

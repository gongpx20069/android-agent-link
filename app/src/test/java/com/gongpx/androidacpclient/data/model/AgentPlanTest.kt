package com.gongpx.androidacpclient.data.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanTest {
    @Test
    fun parsesPlanEntriesAndProgress() {
        val plan = parseAgentPlan(
            """
            [
              {"content":"Inspect code","priority":"high","status":"completed"},
              {"content":"Implement card","priority":"medium","status":"in_progress"},
              {"content":"Run tests","priority":"low","status":"pending"}
            ]
            """.trimIndent(),
        )

        requireNotNull(plan)
        assertEquals(3, plan.entries.size)
        assertEquals(1, plan.completedCount)
        assertFalse(plan.isComplete)
        assertEquals(AgentPlanEntryPriority.High, plan.entries[0].priority)
        assertEquals(AgentPlanEntryStatus.InProgress, plan.entries[1].status)
    }

    @Test
    fun planUpdateCreatesReplaceablePlanMessage() {
        val message = JSONObject(
            """
            {
              "sessionUpdate":"plan",
              "entries":[
                {"content":"Implement card","priority":"high","status":"completed"}
              ]
            }
            """.trimIndent(),
        ).toAgentPlanMessage(nowMillis = 123)

        requireNotNull(message)
        assertEquals(ChatMessageKind.Plan, message.kind)
        assertEquals("agent_plan", message.activityId)
        assertEquals("1/1", message.text)
        assertTrue(requireNotNull(parseAgentPlan(message.details)).isComplete)
    }

    @Test
    fun ignoresInvalidOrEmptyPlanPayloads() {
        assertNull(parseAgentPlan("not-json"))
        assertNull(JSONObject("""{"sessionUpdate":"message"}""").toAgentPlanMessage(1))
    }

    @Test
    fun skipsMalformedEntriesWhenValidPlanStepsRemain() {
        val plan = parseAgentPlan(
            """
            [
              "invalid",
              {"content":"","priority":"low","status":"pending"},
              {"content":{"text":"invalid"},"priority":"low","status":"pending"},
              {"content":"Keep this step","priority":"high","status":"pending"}
            ]
            """.trimIndent(),
        )

        requireNotNull(plan)
        assertEquals(listOf("Keep this step"), plan.entries.map { it.content })
    }

    @Test
    fun malformedLivePlanFallsBackToActivityCard() {
        val missingEntries = JSONObject("""{"sessionUpdate":"plan"}""").toAgentPlanMessage(1)
        val wrongType = JSONObject("""{"sessionUpdate":"plan","entries":"invalid"}""").toAgentPlanMessage(1)

        assertEquals(ChatMessageKind.Activity, requireNotNull(missingEntries).kind)
        assertEquals(ChatMessageKind.Activity, requireNotNull(wrongType).kind)
        assertEquals("agent_plan", missingEntries.activityId)
    }
}

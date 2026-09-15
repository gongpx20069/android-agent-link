package com.gongpx.androidacpclient.data.model

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRecoveryTest {
    @Test
    fun recoveredControlsRetainCommandsAndConfigSemantics() {
        val page = JSONObject("""{"messages":[
            {"kind":"control","details":{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"help"}]}},
            {"kind":"control","details":{"sessionUpdate":"config_option_update","configOptions":[]}},
            {"kind":"control","details":{"sessionUpdate":"config_option_update","configOptions":"invalid"}}
        ]}""").toHistoryPage()
        assertEquals(ChatMessageKind.CommandUpdate, page.messages[0].kind)
        assertEquals(ChatMessageKind.ConfigUpdate, page.messages[1].kind)
        assertEquals(ChatMessageKind.Activity, page.messages[2].kind)
        assertEquals("help", JSONObject(page.messages[0].details!!).getJSONArray("availableCommands").getJSONObject(0).getString("name"))
    }

    @Test
    fun approvalSnapshotParsesTimesAndEmptyIsAuthoritative() {
        val approvals = JSONObject(
            """
            {"type":"approval.snapshot","approvals":[
              {"approvalId":"a","action":"execute","summary":"Run tests","details":{"command":"test"},
               "createdAt":123,"expiresAt":456}
            ]}
            """.trimIndent(),
        ).toApprovalSnapshot()
        assertEquals(1, approvals.size)
        assertEquals(123L, approvals.single().createdAtMillis)
        assertEquals(456L, approvals.single().expiresAtMillis)
        assertEquals("test", JSONObject(approvals.single().details!!).getString("command"))
        assertTrue(JSONObject("""{"approvals":[]}""").toApprovalSnapshot().isEmpty())
        assertTrue(runCatching { JSONObject("{}").toApprovalSnapshot() }.isFailure)
        val legacy = requireNotNull(JSONObject("""{"approvalId":"b"}""").toBridgeApprovalRequest())
        assertEquals(0L, legacy.createdAtMillis)
        assertNull(legacy.expiresAtMillis)
    }

    @Test
    fun approvalRequiresPositiveMatchingAcknowledgement() {
        val acknowledged = JSONObject("""{"approvalId":"a","resolved":true,"status":"approved","decidedAt":987}""")
            .toApprovalDecisionResult("a", "approved")
        assertTrue(acknowledged.resolved)
        assertEquals("approved", acknowledged.status)
        assertEquals(987L, acknowledged.decidedAt)
        assertNull(JSONObject("""{"approvalId":"a","resolved":true,"status":"denied"}""")
            .toApprovalDecisionResult("a", "denied").decidedAt)
        listOf(
            """{"approvalId":"a","resolved":false,"status":"approved"}""",
            """{"approvalId":"a","resolved":true,"status":"expired"}""",
            """{"approvalId":"a","resolved":true,"status":"denied"}""",
            """{"approvalId":"other","resolved":true,"status":"approved"}""",
            """{"approvalId":"a","status":"approved"}""",
            """{"approvalId":"a","resolved":"true","status":"approved"}""",
            """{"approvalId":"a","resolved":true,"status":"approved","error":"failed"}""",
        ).forEach { json ->
            assertTrue(json, runCatching { JSONObject(json).toApprovalDecisionResult("a", "approved") }.isFailure)
        }
    }

    @Test
    fun historyRetainsRichRowsStableIdsAndPagination() {
        val page = JSONObject(
            """
            {"historyId":"snapshot","nextBefore":20,"totalMessages":50,"hasMore":true,"messages":[
              {"role":"user","text":"hello","kind":"message","historyItemId":"h1","timestampMillis":100},
              {"role":"agent","text":"","kind":"Activity","title":"Edit","details":{"content":[{"type":"diff"}]},
               "activityId":"tool1","historyItemId":"h2","timestampMillis":200,"operationId":"op1"},
              {"role":"system","text":"1/2","kind":"plan","details":"[]","historyItemId":"h3","timestampMillis":300},
              {"role":"system","text":"Config","kind":"config_update","details":[],"timestampMillis":400}
            ]}
            """.trimIndent(),
        ).toHistoryPage(999)
        assertEquals("snapshot", page.historyId)
        assertEquals(20, page.nextBefore)
        assertEquals(50, page.totalMessages)
        assertTrue(page.hasMore)
        assertEquals(4, page.messages.size)
        assertEquals("h1", page.messages[0].activityId)
        assertEquals(100L, page.messages[0].timestampMillis)
        assertEquals(ChatMessageKind.Activity, page.messages[1].kind)
        assertEquals("Edit", page.messages[1].title)
        assertEquals("tool1", page.messages[1].activityId)
        assertEquals("op1", page.messages[1].operationId)
        assertEquals("diff", JSONObject(page.messages[1].details!!).getJSONArray("content")
            .getJSONObject(0).getString("type"))
        assertEquals(ChatMessageKind.Plan, page.messages[2].kind)
        assertEquals(MessageRole.System, page.messages[2].role)
        assertEquals("[]", page.messages[2].details)
        assertEquals(ChatMessageKind.ConfigUpdate, page.messages[3].kind)
    }

    @Test
    fun historySupportsLegacyAndFinalPageWithoutNullStringIds() {
        val legacy = JSONObject("""{"messages":[{"role":"agent","text":"hello","messageId":"m"}]}""")
            .toHistoryPage(123)
        assertEquals("m", legacy.messages.single().activityId)
        assertEquals(123L, legacy.messages.single().timestampMillis)
        assertEquals(1, legacy.totalMessages)
        assertNull(legacy.historyId)
        assertNull(legacy.nextBefore)
        assertFalse(legacy.hasMore)
        val finalPage = JSONObject("""{"messages":[],"historyId":null,"nextBefore":null,"hasMore":false}""")
            .toHistoryPage()
        assertNull(finalPage.historyId)
        assertNull(finalPage.nextBefore)
    }

    @Test
    fun genericControlRowsAreActivitiesNotTextBubbles() {
        val page = JSONObject(
            """{"messages":[{"role":"agent","text":"hello","kind":"text"},
               {"role":"system","text":"","kind":"control","details":"opaque control data"}]}""",
        ).toHistoryPage()
        assertEquals(ChatMessageKind.Message, page.messages[0].kind)
        assertEquals(ChatMessageKind.Activity, page.messages[1].kind)
        assertEquals("opaque control data", page.messages[1].details)
        assertEquals(1, page.totalMessages)
    }

    @Test
    fun backendReplayRetainsFullUpdateStringAndZeroTimestamp() {
        val update = """{"sessionUpdate":"plan","entries":[{"content":"Inspect","status":"pending"}]}"""
        val page = JSONObject(
            """{"historyId":"snapshot","nextBefore":7,"totalMessages":12,"hasMore":true,"messages":[]}""",
        ).apply {
            getJSONArray("messages").put(
                JSONObject()
                    .put("kind", "plan")
                    .put("role", "agent")
                    .put("text", "")
                    .put("title", "")
                    .put("activityId", "")
                    .put("timestampMillis", 0)
                    .put("historyItemId", "snapshot:7")
                    .put("details", update),
            )
        }.toHistoryPage(999)
        assertEquals(7, page.nextBefore)
        assertEquals(12, page.totalMessages)
        val message = page.messages.single()
        assertEquals(ChatMessageKind.Plan, message.kind)
        assertEquals(update, message.details)
        assertEquals("snapshot:7", message.activityId)
        assertEquals(0L, message.timestampMillis)
    }

    @Test
    fun recentLoadPreservesSessionSwitchCheckpointWhilePaginationDoesNotInventOne() {
        val recent = JSONObject(
            """{"messages":[],"historyId":"snapshot","eventGeneration":"generation-new","latestEventId":0}""",
        ).toHistoryPage()
        assertEquals("generation-new", recent.eventGeneration)
        assertEquals(0, recent.latestEventId)
        val page = JSONObject("""{"messages":[],"historyId":"snapshot","nextBefore":null}""").toHistoryPage()
        assertNull(page.eventGeneration)
        assertNull(page.latestEventId)
    }

    @Test
    fun missingOrFailedResultNeverBecomesSuccessfulEmptyList() {
        val type = "session.list.result"
        listOf(
            emptyList(),
            listOf(JSONObject("""{"type":"session.list.result","error":"session failed"}""")),
            listOf(JSONObject("""{"type":"bridge.error","error":"failed"}""")),
            listOf(JSONObject("""{"type":"session.list.result","sessions":[]}"""),
                JSONObject("""{"type":"operation.done","status":"failed"}""")),
        ).forEach { events ->
            assertTrue(runCatching { requireBridgeResult(events, type) }.exceptionOrNull() is IOException)
        }
        assertEquals(0, requireBridgeResult(
            listOf(JSONObject("""{"type":"session.list.result","sessions":[]}""")), type,
        ).getJSONArray("sessions").length())
        assertTrue(runCatching { JSONObject("""{"error":"snapshot expired"}""").toHistoryPage() }.isFailure)
    }

    @Test
    fun authenticationAndTransportErrorsAreTypedSafeAndActionable() {
        val unauthorized = bridgeConnectionFailure(401)
        val forbidden = bridgeConnectionFailure(403)
        assertEquals(BridgeConnectionFailureCategory.Authentication, unauthorized.category)
        assertEquals(BridgeConnectionFailureCategory.Authentication, forbidden.category)
        assertTrue(unauthorized.authenticationRequired)
        assertTrue(forbidden.authenticationRequired)
        assertTrue(unauthorized.message!!.contains("Re-pair"))
        assertTrue(forbidden.message!!.contains("permissions"))
        assertEquals(401, unauthorized.httpStatus)
        assertEquals(403, forbidden.httpStatus)
        assertNull(unauthorized.cause)
        assertNull(forbidden.cause)
        val transport = bridgeConnectionFailure()
        assertEquals(BridgeConnectionFailureCategory.Transport, transport.category)
        assertFalse(transport.authenticationRequired)
        assertTrue(transport.message!!.contains("reconnect"))
        assertFalse(transport.message!!.contains("token="))
    }
}

package com.gongpx.androidacpclient.integration

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IntegrationProtocolTest {
    @Test fun toolsHaveIndependentPermissionsAndNoApprovalBypass() {
        assertEquals("read", resolveToolAction("agentlink_workspace", "list").permission)
        assertEquals("create", resolveToolAction("agentlink_workspace", "create").permission)
        assertEquals("create", resolveToolAction("agentlink_chat", "create").permission)
        assertEquals("control", resolveToolAction("agentlink_control", "send").permission)
        assertEquals("task.cancel", resolveToolAction("agentlink_control", "cancel").bridgeAction)
        assertThrows(ControlFailure::class.java) { resolveToolAction("agentlink_control", "approve") }
        assertThrows(ControlFailure::class.java) { resolveToolAction("agentlink_chat", "workspace.create") }
    }

    @Test fun sendsRequireHumanRevisionAndForceMochiSource() {
        val spec = resolveToolAction("agentlink_control", "send")
        val args = JSONObject().put("action", "send").put("machineId", "m").put("chatId", "c")
            .put("operationId", "op").put("content", "hello")
        assertThrows(ControlFailure::class.java) { checkedArguments(args, spec) }
        args.put("expectedHumanRevision", 4)
        val wire = checkedArguments(args, spec)
        assertEquals("mochi", wire.getString("source"))
        assertEquals(4, wire.getLong("expectedHumanRevision"))
        assertFalse(wire.has("machineId"))
        args.put("source", "mochi")
        assertEquals("mochi", checkedArguments(args, spec).getString("source"))
        args.put("source", "human")
        assertThrows(ControlFailure::class.java) { checkedArguments(args, spec) }
    }

    @Test fun credentialsAndUnknownFieldsCannotReachTheBridge() {
        for (key in listOf("endpoint", "deviceToken", "connectionHeaders", "type", "requestId", "command")) {
            val args = JSONObject().put("action", "list").put("machineId", "m").put(key, "spoof")
            assertThrows(ControlFailure::class.java) {
                checkedArguments(args, resolveToolAction("agentlink_workspace", "list"))
            }
        }
    }

    @Test fun readPagesAreBounded() {
        val spec = resolveToolAction("agentlink_chat", "read")
        assertEquals(50, checkedArguments(JSONObject().put("chatId", "c"), spec).getInt("limit"))
        for (limit in listOf(0, 101, -1)) {
            assertThrows(ControlFailure::class.java) { checkedArguments(JSONObject().put("limit", limit), spec) }
        }

        assertThrows(ControlFailure::class.java) { checkedArguments(JSONObject().put("afterEventId", -1), spec) }
    }

    @Test fun cloneAndListMatchBridgeFields() {
        val clone = checkedArguments(JSONObject().put("url", "https://example.test/repo"),
            resolveToolAction("agentlink_workspace", "create"))
        assertEquals("https://example.test/repo", clone.getString("repositoryUrl"))
        assertFalse(clone.has("url"))
        val page = checkedArguments(JSONObject().put("workspaceId", "workspace").put("offset", 100),
            resolveToolAction("agentlink_chat", "list"))
        assertEquals("workspace", page.getString("workspaceId"))
        assertEquals(100, page.getInt("offset"))
    }

    @Test fun transportBudgetIncludesUtf8AndBinderUtf16() {
        assertTrue(fitsBinderLimit("a".repeat(MAX_ARGUMENT_BYTES / 2), MAX_ARGUMENT_BYTES))
        assertFalse(fitsBinderLimit("a".repeat(MAX_ARGUMENT_BYTES / 2 + 1), MAX_ARGUMENT_BYTES))
        assertFalse(fitsBinderLimit("中".repeat(MAX_ARGUMENT_BYTES / 3 + 1), MAX_ARGUMENT_BYTES))
        assertTrue(fitsBinderLimit(okResult(JSONObject().put("authorized", false)).toString(), MAX_RESULT_BYTES))
    }

    @Test fun grantIsBoundToPackageAndExactCurrentSignerSet() {
        val grant = IntegrationGrant("app.mochi", setOf("signer"), setOf("machine"), setOf("read"), 1)
        val caller = CallerIdentity("app.mochi", 10001, "Mochi", setOf("signer"))
        assertTrue(grant.matches(caller))
        assertFalse(grant.matches(caller.copy(packageName = "app.spoof")))
        assertFalse(grant.matches(caller.copy(signers = setOf("new-signer"))))
        assertFalse(grant.matches(caller.copy(signers = setOf("signer", "other"))))
        assertFalse(grant.copy(signers = emptySet()).matches(caller.copy(signers = emptySet())))
    }
}

package com.gongpx.androidacpclient.integration

import org.json.JSONObject

internal const val MAX_ARGUMENT_BYTES = 128 * 1024
internal const val MAX_RESULT_BYTES = 256 * 1024
internal const val AUTHORIZE = "com.gongpx.androidacpclient.AUTHORIZE"
internal const val OPEN_CHAT = "com.gongpx.androidacpclient.OPEN_CHAT"
internal const val OPEN_APPROVAL = "com.gongpx.androidacpclient.OPEN_APPROVAL"
internal const val MANAGE_ACCESS = "com.gongpx.androidacpclient.MANAGE_ACCESS"

internal class ControlFailure(val code: String, override val message: String, val needsApproval: Boolean = false) :
    Exception(message)

internal fun errorResult(code: String, message: String, needsApproval: Boolean = false): JSONObject =
    JSONObject().put("status", "error").put("code", code).put("message", message).apply {
        if (needsApproval) put("needsApproval", true)
    }

internal fun okResult(data: JSONObject): JSONObject = JSONObject().put("status", "ok").put("data", data)

internal fun fitsBinderLimit(text: String, limit: Int): Boolean =
    text.length.toLong() * 2 <= limit && text.toByteArray(Charsets.UTF_8).size <= limit

private fun JSONObject.isNonnegativeInteger(key: String): Boolean {
    val value = opt(key)
    return (value is Int || value is Long) && (value as Number).toLong() >= 0
}

internal data class ToolAction(val bridgeAction: String, val permission: String, val fields: Set<String>)

internal fun resolveToolAction(method: String, action: String): ToolAction {
    val result = when (method to action) {
        "agentlink_workspace" to "list" -> ToolAction("workspace.list", "read", emptySet())
        "agentlink_workspace" to "create" -> ToolAction("workspace.create", "create",
            setOf("mode", "path", "displayName", "name", "sourceWorkspaceId", "branch", "url", "repositoryUrl"))
        "agentlink_chat" to "list" -> ToolAction("chat.list", "read", setOf("workspaceId", "offset", "limit"))
        "agentlink_chat" to "read" -> ToolAction("chat.read", "read", setOf("chatId", "afterEventId", "limit"))
        "agentlink_chat" to "create" -> ToolAction("chat.create", "create", setOf("workspaceId", "agentId", "title", "chatId"))
        "agentlink_control" to "send" -> ToolAction("chat.send", "control",
            setOf("chatId", "content", "operationId", "expectedHumanRevision"))
        "agentlink_control" to "cancel" -> ToolAction("task.cancel", "control", setOf("chatId", "operationId"))
        "agentlink_control" to "configure" -> ToolAction("chat.configure", "control", setOf("chatId", "configId", "value"))
        else -> throw ControlFailure("INVALID_ARGS", "Unknown tool action.")
    }
    return result
}

internal fun checkedArguments(arguments: JSONObject, action: ToolAction): JSONObject {
    val allowed = action.fields + setOf("action", "machineId") +
        if (action.bridgeAction == "chat.send") setOf("source") else emptySet()
    if (arguments.keys().asSequence().any { it !in allowed }) {
        throw ControlFailure("INVALID_ARGS", "Unsupported argument. Credentials, endpoints and source cannot be supplied.")
    }
    val payload = JSONObject()
    action.fields.forEach { field -> if (arguments.has(field)) payload.put(field, arguments.get(field)) }
    if (action.bridgeAction == "workspace.create" && payload.has("url")) {
        if (payload.has("repositoryUrl")) throw ControlFailure("INVALID_ARGS", "Supply only one repository URL.")
        payload.put("repositoryUrl", payload.remove("url"))
    }
    if (action.bridgeAction == "chat.send") {
        if (arguments.has("source") && arguments.opt("source") != "mochi") {
            throw ControlFailure("INVALID_ARGS", "The integration source must be mochi.")
        }
        if (arguments.opt("content") !is String || arguments.getString("content").isBlank() ||
            arguments.opt("operationId") !is String || arguments.getString("operationId").isBlank() ||
            !arguments.isNonnegativeInteger("expectedHumanRevision")
        ) throw ControlFailure("INVALID_ARGS", "Send requires content, operationId and the humanRevision from a fresh read.")
        payload.put("source", "mochi")
    }
    if (action.bridgeAction in setOf("chat.read", "chat.list")) {
        val limit = arguments.optInt("limit", 50)
        if (limit !in 1..100 ||
            (arguments.has("limit") && !arguments.isNonnegativeInteger("limit")) ||
            (arguments.has("afterEventId") && !arguments.isNonnegativeInteger("afterEventId")) ||
            (arguments.has("offset") && !arguments.isNonnegativeInteger("offset"))) {
            throw ControlFailure("INVALID_ARGS", "Read requires limit 1..100 and a nonnegative cursor.")
        }
        payload.put("limit", limit)
    }
    return payload
}

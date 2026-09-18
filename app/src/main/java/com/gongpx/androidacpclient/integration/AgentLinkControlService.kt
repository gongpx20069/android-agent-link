package com.gongpx.androidacpclient.integration

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.gongpx.androidacpclient.data.bridge.BridgeClient
import com.gongpx.androidacpclient.data.store.MachineStore
import com.gongpx.androidacpclient.data.tunnel.TunnelAccounts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Credential-owning same-device proxy. The exported Binder itself confers no authority. */
class AgentLinkControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = mutableMapOf<Int, Int>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        receive(message)
        true
    })
    private val bridge by lazy { BridgeClient(TunnelAccounts.get(this)::relayHeaders) }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun receive(message: Message) {
        if (message.what != 1) return
        val reply = message.replyTo ?: return
        val uid = message.sendingUid
        var requestId = ""
        try {
            val data = message.data
            requestId = data.getString("requestId").orEmpty()
            val method = data.getString("method").orEmpty()
            val raw = data.getString("arguments").orEmpty()
            if (!requestId.matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
                !fitsBinderLimit(raw, MAX_ARGUMENT_BYTES) || method.length > 64
            ) throw ControlFailure("INVALID_ARGS", "Invalid request ID or oversized arguments.")
            if (inFlight.values.sum() >= 8 || (inFlight[uid] ?: 0) >= 2) {
                throw ControlFailure("PROVIDER_ERROR", "Too many requests in flight.")
            }
            inFlight[uid] = (inFlight[uid] ?: 0) + 1
            val id = requestId
            scope.launch {
                val result = try {
                    withTimeout(30_000) { execute(uid, method, JSONObject(raw)) }
                } catch (_: TimeoutCancellationException) {
                    errorResult("TIMEOUT", "Timed out. Remote work may still be running; read state before retrying.")
                } catch (error: ControlFailure) {
                    errorResult(error.code, error.message, error.needsApproval)
                } catch (_: SecurityException) {
                    errorResult("PERMISSION_DENIED", "Caller authorization is missing, revoked or its signer changed.")
                        .put("needsAuthorization", true)
                } catch (_: org.json.JSONException) {
                    errorResult("INVALID_ARGS", "Arguments or provider response are not valid JSON.")
                } catch (_: CancellationException) {
                    errorResult("CANCELLED", "Local request cancelled. This does not cancel remote work.")
                } catch (_: Exception) {
                    errorResult("PROVIDER_ERROR", "AgentLink could not reach the paired bridge. Open AgentLink to check its connection.")
                }
                respond(reply, id, result)
                mainHandler.post {
                    val remaining = (inFlight[uid] ?: 1) - 1
                    if (remaining <= 0) inFlight.remove(uid) else inFlight[uid] = remaining
                }
            }
        } catch (error: Exception) {
            respond(reply, requestId.take(128), errorResult(
                (error as? ControlFailure)?.code ?: "INVALID_ARGS",
                (error as? ControlFailure)?.message ?: "Invalid Messenger request.",
            ))
        }
    }

    private suspend fun execute(uid: Int, method: String, arguments: JSONObject): JSONObject {
        val identity = identityForUid(this, uid)
        val grants = IntegrationGrants(this)
        val grant = grants.find(identity)
        val action = arguments.optString("action")
        if (method == "revoke" || (method == "agentlink_control" && action == "revoke")) {
            grants.revoke(identity.packageName)
            return okResult(JSONObject().put("protocolVersion", 1).put("authorized", false).put("needsAuthorization", true))
        }
        if (method == "status" || (method == "agentlink_control" && action == "status")) {
            return okResult(JSONObject().put("protocolVersion", 1).put("authorized", grant != null)
                .put("needsAuthorization", grant == null).put("connected", true).apply {
                    if (grant != null) {
                        put("permissions", JSONArray(grant.permissions.toList()))
                        put("machines", machineSummaries(grant))
                    }
                })
        }
        if (grant == null) throw SecurityException("Not granted.")
        if (method == "agentlink_workspace" && action == "machines") {
            requirePermission(grant, "read")
            return okResult(JSONObject().put("machines", machineSummaries(grant)))
        }
        val spec = resolveToolAction(method, action)
        requirePermission(grant, spec.permission)
        val machineId = arguments.optString("machineId")
        if (machineId !in grant.machineIds) throw SecurityException("Machine outside grant.")
        val payload = checkedArguments(arguments, spec)
        if (spec.bridgeAction == "chat.configure") {
            throw ControlFailure("PERMISSION_DENIED", "Session configuration requires a human decision in AgentLink. Open the chat.", true)
        }
        val machine = MachineStore(this).load().firstOrNull { it.id == machineId }
            ?: throw ControlFailure("NOT_FOUND", "Paired machine no longer exists.")
        // Recheck immediately before crossing the network trust boundary, including signer replacement.
        val currentGrant = grants.find(identityForUid(this, uid)) ?: throw SecurityException("Grant revoked.")
        requirePermission(currentGrant, spec.permission)
        if (machineId !in currentGrant.machineIds) throw SecurityException("Machine grant revoked.")
        val response = bridge.controlRequest(machine, spec.bridgeAction, payload)
        if (response.optString("status") == "error") {
            val code = response.optString("code").takeIf { it in ERROR_CODES } ?: "PROVIDER_ERROR"
            return errorResult(code, response.optString("message", "Bridge operation failed.").take(1024))
        }
        if (response.optString("status") != "ok" || response.optJSONObject("data") == null) {
            throw ControlFailure("PROVIDER_ERROR", "Bridge returned an invalid control response.")
        }
        // Revocation also stops an already-started read from handing data back.
        val finalGrant = grants.find(identityForUid(this, uid)) ?: throw SecurityException("Grant revoked.")
        requirePermission(finalGrant, spec.permission)
        if (machineId !in finalGrant.machineIds) throw SecurityException("Machine grant revoked.")
        return okResult(response.getJSONObject("data"))
    }

    private fun machineSummaries(grant: IntegrationGrant): JSONArray =
        JSONArray(MachineStore(this).load().filter { it.id in grant.machineIds }.map {
            JSONObject().put("machineId", it.id).put("displayName", it.displayName)
        })

    private fun requirePermission(grant: IntegrationGrant, permission: String) {
        if (permission !in grant.permissions) throw SecurityException("Missing permission.")
    }

    private fun respond(reply: Messenger, requestId: String, result: JSONObject) {
        val raw = result.toString()
        val bounded = if (fitsBinderLimit(raw, MAX_RESULT_BYTES)) raw else
            errorResult("PROVIDER_ERROR", "Response exceeds 256 KiB. Read a smaller page; no data was silently truncated.").toString()
        try {
            reply.send(Message.obtain(null, 2).apply {
                data = Bundle().apply { putString("requestId", requestId); putString("result", bounded) }
            })
        } catch (_: Exception) {
            // Reply Binder death is transport cancellation, never a remote task.cancel.
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val ERROR_CODES = setOf("PERMISSION_DENIED", "INVALID_ARGS", "CONFLICT", "NOT_FOUND",
            "PROVIDER_ERROR", "TIMEOUT", "CANCELLED")
    }
}

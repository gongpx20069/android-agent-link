package com.gongpx.androidacpclient.integration

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gongpx.androidacpclient.MainActivity
import com.gongpx.androidacpclient.data.bridge.BridgeClient
import com.gongpx.androidacpclient.data.bridge.mergeSharedChat
import com.gongpx.androidacpclient.data.notification.EXTRA_CHAT_ID
import com.gongpx.androidacpclient.data.store.MachineStore
import com.gongpx.androidacpclient.data.store.ChatStore
import com.gongpx.androidacpclient.data.tunnel.TunnelAccounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AgentLinkAuthorizationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var nonce = ""
    private var openingChat = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        nonce = intent.getStringExtra("requestId").orEmpty()
        setResult(RESULT_CANCELED)
        val callerPackage = callingPackage
        if (callerPackage == null || callingActivity?.packageName != callerPackage ||
            !nonce.matches(Regex("[A-Za-z0-9_-]{16,128}")) ||
            intent.action !in setOf(AUTHORIZE, OPEN_CHAT, OPEN_APPROVAL, MANAGE_ACCESS)
        ) {
            finish()
            return
        }
        openingChat = savedInstanceState?.getBoolean("openingChat") ?: false
        if (openingChat) return
        val content = contentColumn()
        content.addView(label("Verifying installed caller…"))
        scope.launch {
            try {
                val identity = withContext(Dispatchers.IO) { callerIdentity(this@AgentLinkAuthorizationActivity, callerPackage) }
                when (intent.action) {
                    AUTHORIZE -> showAuthorization(content, identity)
                    MANAGE_ACCESS -> {
                        openingChat = true
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent(this@AgentLinkAuthorizationActivity, IntegrationGrantsActivity::class.java), OPEN_REQUEST)
                    }
                    else -> openTrustedChat(identity)
                }
            } catch (_: Exception) {
                content.removeAllViews()
                content.addView(label("Unable to authorize this caller. Reopen Mochi and try again. No access was granted."))
                content.addView(button("Back") { finish() })
            }
        }
    }

    private suspend fun showAuthorization(content: LinearLayout, identity: CallerIdentity) {
        val machines = withContext(Dispatchers.IO) { MachineStore(this@AgentLinkAuthorizationActivity).load() }
        content.removeAllViews()
        content.addView(label("Connect to AgentLink"))
        content.addView(label("Installed caller: ${identity.label}\nPackage: ${identity.packageName}\nUID: ${identity.uid}\nSHA-256 signer:\n${identity.signers.sorted().joinToString("\n")}"))
        content.addView(label("Only approve an app you trust. Separate APK signatures are allowed only by your confirmation here. " +
            "AgentLink keeps all connection credentials. Access covers ALL workspaces and chats on each selected machine, including future chats."))
        val machineChecks = machines.map { machine ->
            CheckBox(this).apply { text = "${machine.displayName} (${machine.id})"; isChecked = false }
                .also(content::addView) to machine.id
        }
        if (machines.isEmpty()) content.addView(label("No paired machines. Pair a machine in AgentLink first."))
        val read = CheckBox(this).apply { text = "Read workspaces, chats, messages and task status"; isChecked = true }
        val control = CheckBox(this).apply { text = "Control: send prompts and request task cancellation" }
        val create = CheckBox(this).apply { text = "Create workspaces and chats (may clone or change remote files)" }
        listOf(read, control, create).forEach(content::addView)
        content.addView(label("Read, control and create are separate grants. Agent execution still uses the shared session's approval policy. " +
            "Mochi cannot approve requests or change permission settings. Human input overrides stale follow-ups."))
        val feedback = label("")
        content.addView(feedback)
        val approve = button("Authorize selected access and return") {}
        approve.setOnClickListener {
            val ids = machineChecks.filter { it.first.isChecked }.map { it.second }.toSet()
            val permissions = buildSet {
                if (read.isChecked) add("read")
                if (control.isChecked) add("control")
                if (create.isChecked) add("create")
            }
            if (ids.isEmpty() || permissions.isEmpty()) {
                feedback.text = "Select at least one machine and one permission."
                return@setOnClickListener
            }
            approve.isEnabled = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val now = callerIdentity(this@AgentLinkAuthorizationActivity, identity.packageName, identity.uid)
                        check(now.signers == identity.signers)
                        IntegrationGrants(this@AgentLinkAuthorizationActivity).save(now, ids, permissions)
                    }
                    returnToCaller()
                } catch (_: Exception) {
                    feedback.text = "Authorization could not be saved. No success was returned."
                    approve.isEnabled = true
                }
            }
        }
        content.addView(approve)
        content.addView(button("Deny and return") { finish() })
    }

    private suspend fun openTrustedChat(identity: CallerIdentity) {
        val machineId = intent.getStringExtra("machineId").orEmpty()
        val chatId = intent.getStringExtra("chatId").orEmpty()
        require(chatId.isNotBlank() && chatId.length <= 256)
        withContext(Dispatchers.IO) {
            val grants = IntegrationGrants(this@AgentLinkAuthorizationActivity)
            val grant = grants.find(identity) ?: throw SecurityException()
            if ("read" !in grant.permissions || machineId !in grant.machineIds) throw SecurityException()
            val machine = MachineStore(this@AgentLinkAuthorizationActivity).load().first { it.id == machineId }
            val result = BridgeClient(TunnelAccounts.get(this@AgentLinkAuthorizationActivity)::relayHeaders)
                .controlRequest(machine, "chat.read", JSONObject().put("chatId", chatId).put("limit", 1))
            check(result.optString("status") == "ok")
            val latest = grants.find(callerIdentity(this@AgentLinkAuthorizationActivity, identity.packageName, identity.uid))
                ?: throw SecurityException()
            if (machineId !in latest.machineIds || "read" !in latest.permissions) throw SecurityException()
            val store = ChatStore(this@AgentLinkAuthorizationActivity)
            val remote = result.getJSONObject("data").getJSONObject("chat")
            val local = store.load().firstOrNull { it.id == chatId }
            store.upsert(mergeSharedChat(machine, remote, local))
            store.awaitDurable()
        }
        openingChat = true
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, MainActivity::class.java).putExtra(EXTRA_CHAT_ID, chatId), OPEN_REQUEST)
    }

    @Deprecated("Activity result is retained for the explicit same-device IPC contract.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OPEN_REQUEST) returnToCaller()
    }

    private fun returnToCaller() {
        setResult(RESULT_OK, Intent().putExtra("requestId", nonce).putExtra("protocolVersion", 1))
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("openingChat", openingChat)
        super.onSaveInstanceState(outState)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0) return false
        return super.dispatchTouchEvent(event)
    }

    private fun contentColumn(): LinearLayout {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            filterTouchesWhenObscured = true
        }
        setContentView(ScrollView(this).apply { addView(column) })
        return column
    }
    private fun label(value: String) = TextView(this).apply { text = value; textSize = 16f; setPadding(0, 16, 0, 16) }
    private fun button(value: String, action: (View) -> Unit) = Button(this).apply {
        text = value
        filterTouchesWhenObscured = true
        setOnClickListener(action)
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private companion object { const val OPEN_REQUEST = 7301 }
}

/** Non-exported: only AgentLink Settings may manage grants. */
class IntegrationGrantsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0) return false
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        refresh()
    }

    private fun refresh() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            filterTouchesWhenObscured = true
        }
        setContentView(ScrollView(this).apply { addView(column) })
        column.addView(TextView(this).apply { text = "Connected apps\nRevoke access at any time. Revocation does not cancel running remote tasks."; textSize = 20f })
        scope.launch {
            try {
                val grants = withContext(Dispatchers.IO) { IntegrationGrants(this@IntegrationGrantsActivity).all() }
                if (grants.isEmpty()) column.addView(TextView(this@IntegrationGrantsActivity).apply { text = "No authorized apps." })
                grants.forEach { grant ->
                    column.addView(TextView(this@IntegrationGrantsActivity).apply {
                        text = "\n${grant.packageName}\nSigners: ${grant.signers.joinToString()}\nMachines: ${grant.machineIds.joinToString()}\nPermissions: ${grant.permissions.joinToString()}"
                    })
                    column.addView(Button(this@IntegrationGrantsActivity).apply {
                        text = "Revoke ${grant.packageName}"
                        filterTouchesWhenObscured = true
                        setOnClickListener {
                            isEnabled = false
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { IntegrationGrants(this@IntegrationGrantsActivity).revoke(grant.packageName) }
                                    refresh()
                                } catch (_: Exception) { text = "Could not revoke. Retry."; isEnabled = true }
                            }
                        }
                    })
                }
            } catch (_: Exception) {
                column.addView(TextView(this@IntegrationGrantsActivity).apply { text = "Unable to load authorization records." })
            }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

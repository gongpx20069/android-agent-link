package com.gongpx.androidacpclient.data.notification

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.gongpx.androidacpclient.R
import com.gongpx.androidacpclient.data.bridge.BridgeClient
import com.gongpx.androidacpclient.data.bridge.restoreQueuedPrompts
import com.gongpx.androidacpclient.data.bridge.ChatConnection
import com.gongpx.androidacpclient.data.model.ApprovalStatus
import com.gongpx.androidacpclient.data.model.BridgeApprovalRequest
import com.gongpx.androidacpclient.data.model.BridgeConnectionException
import com.gongpx.androidacpclient.data.model.BridgeConnectionFailureCategory
import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.model.ChatMessageKind
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.model.MessageRole
import com.gongpx.androidacpclient.data.model.acceptPrompt
import com.gongpx.androidacpclient.data.model.bindAcpSession
import com.gongpx.androidacpclient.data.model.bridgeConnectionFailure
import com.gongpx.androidacpclient.data.model.finishPrompt
import com.gongpx.androidacpclient.data.model.isActionable
import com.gongpx.androidacpclient.data.model.isTerminalPromptStatus
import com.gongpx.androidacpclient.data.model.mergeTimelineMessage
import com.gongpx.androidacpclient.data.model.recordBridgeEventId
import com.gongpx.androidacpclient.data.model.startQueuedPrompt
import com.gongpx.androidacpclient.data.model.toApproval
import com.gongpx.androidacpclient.data.store.ApprovalStore
import com.gongpx.androidacpclient.data.store.ChatStore
import com.gongpx.androidacpclient.data.store.MachineStore
import com.gongpx.androidacpclient.data.tunnel.TunnelAccounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Activity must release all writers and connections before start, and call stop on the
 * main thread before loading stores. No active prompt is reconstructed from message history.
 */
class ChatMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bridgeClient: BridgeClient
    private val monitored = linkedMapOf<String, MonitoredChat>()
    private lateinit var notifications: ChatNotificationManager
    private lateinit var chatStore: ChatStore
    private lateinit var approvalStore: ApprovalStore
    private var acceptingCallbacks = false
    private var deadlineElapsedMillis = Long.MAX_VALUE
    private var ownedRequestEpoch = -1L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null

    private class LaunchRequest(val epoch: Long, val chatIds: Set<String>)

    private class MonitoredChat(var chat: Chat, val machine: Machine) {
        var connection: ChatConnection? = null
        var connectionEpoch = 0
        var retryAttempt = 0
        var snapshotReceived = false
        var remoteQueueCount = 0
        val activeOperations = mutableSetOf<String>()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        notifications = ChatNotificationManager(this)
        chatStore = ChatStore(this)
        bridgeClient = BridgeClient(
            TunnelAccounts.get(this)::relayHeaders, chatStore::awaitDurable, { chatStore.batch(it) }, chatStore::readyForEvent,
        )
        scope.launch {
            chatStore.failure.collect { failure ->
                if (failure != null && acceptingCallbacks) stopWithWarning(R.string.monitor_storage_error)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ownedRequestEpoch = intent?.getLongExtra(EXTRA_REQUEST_EPOCH, -1) ?: -1
        val request = launchRequest?.takeIf { it.epoch == ownedRequestEpoch }
        val ids = request?.chatIds ?: intent?.getStringArrayListExtra(EXTRA_CHAT_IDS).orEmpty().toSet()
        // Even a cancelled, already queued startForegroundService request must be promoted.
        try {
            val notification = notifications.ongoingNotification(ids.size)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: RuntimeException) {
            persistStartFailure(this@ChatMonitorService, ids)
            finishMonitoring()
            return START_NOT_STICKY
        }
        if (ownedRequestEpoch != requestEpoch || request == null || ids.isEmpty()) {
            if (launchRequest != null && launchRequest?.epoch != ownedRequestEpoch) return START_NOT_STICKY
            finishMonitoring()
            return START_NOT_STICKY
        }

        deadlineElapsedMillis = SystemClock.elapsedRealtime() + MAX_RUNTIME_MILLIS
        beginMonitoring(ids)
        return START_NOT_STICKY
    }

    private fun beginMonitoring(ids: Set<String>) {
        releaseOwnership()
        acceptingCallbacks = true
        val request = ownedRequestEpoch
        loadJob = scope.launch {
            try {
                val machines = withContext(Dispatchers.IO) {
                    approvalStore = ApprovalStore(this@ChatMonitorService)
                    MachineStore(this@ChatMonitorService).load().associateBy { it.id }
                }
                val chats = chatStore.loadAsync()
                if (!acceptingCallbacks || request != ownedRequestEpoch || request != requestEpoch) return@launch
                chats.filter { it.id in ids }.forEach { chat ->
                    val machine = machines[chat.machineId]
                    when {
                        chat.bridgeResyncRequired -> warn(chat, R.string.monitor_resync_error)
                        machine == null -> warn(chat, R.string.monitor_machine_missing)
                        else -> monitored[chat.id] = MonitoredChat(chat, machine)
                    }
                }
                if (monitored.isEmpty()) {
                    finishMonitoring()
                } else {
                    handler.postDelayed(
                        { stopWithWarning(R.string.monitor_timeout) },
                        (deadlineElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0),
                    )
                    monitored.values.toList().forEach { connect(it) }
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                persistStartFailure(this@ChatMonitorService, ids)
                stopWithWarning(R.string.monitor_storage_error)
            }
        }
    }

    private fun connect(state: MonitoredChat) {
        if (!owns(state)) return
        if (SystemClock.elapsedRealtime() >= deadlineElapsedMillis) {
            stopWithWarning(R.string.monitor_timeout)
            return
        }
        state.connection?.close()
        val epoch = ++state.connectionEpoch
        state.snapshotReceived = false
        val chat = state.chat
        fun apply(action: () -> Unit) {
            if (!owns(state) || state.connectionEpoch != epoch) return
            if (SystemClock.elapsedRealtime() >= deadlineElapsedMillis) {
                stopWithWarning(R.string.monitor_timeout)
                return
            }
            try {
                action()
            } catch (_: RuntimeException) {
                stopWithWarning(R.string.monitor_storage_error)
            }
        }
        try {
            state.connection = bridgeClient.openChatConnection(
                machine = state.machine,
                chatId = chat.id,
                chatTitle = chat.title,
                agentId = chat.agentId,
                workspacePath = chat.workspacePath,
                lastEventId = chat.lastBridgeEventId,
                lastEventGeneration = chat.bridgeEventGeneration,
                sessionId = chat.acpSessionId,
                sessionResumable = chat.acpSessionResumable,
                // Do not resend durable queue entries until attach has ruled out a history gap/reset.
                queuedPrompts = emptyList(),
                initialMessages = chat.messages,
                onMessage = { message, _ ->
                    apply { persist(state, state.chat.copy(messages = state.chat.messages.mergeTimelineMessage(message))) }
                },
                onApproval = { request, _ -> apply { receiveApproval(state, request) } },
                onApprovalSnapshot = { requests ->
                    apply {
                        val previous = approvalStore.load().filter { it.chatId == chat.id }
                        approvalStore.reconcile(state.chat, requests)
                        val current = approvalStore.load().filter { it.chatId == chat.id }
                        previous.filter { old -> current.none { it.id == old.id && it.status == ApprovalStatus.Pending } }
                            .forEach { notifications.cancelApproval(it.id) }
                        current.filter { it.status == ApprovalStatus.Pending }
                            .filter { item -> previous.none { it.id == item.id && it.status == ApprovalStatus.Pending } }
                            .forEach { notifications.showApproval(it) }
                    }
                },
                onApprovalResolved = { id, status, decidedAt ->
                    apply {
                        approvalStore.resolve(id, status, decidedAt)
                        notifications.cancelApproval(id)
                    }
                },
                onPromptAccepted = { id, status, content, _ ->
                    apply {
                        var next = state.chat.acceptPrompt(id, status, content, System.currentTimeMillis())
                        if (status in setOf("starting", "running", "")) {
                            state.activeOperations.add(id)
                            next = next.copy(agentStatus = "busy")
                        }
                        persist(state, next)
                    }
                },
                onPromptStarted = { id, content, _ ->
                    apply {
                        state.activeOperations.add(id)
                        persist(state, state.chat.startQueuedPrompt(id, content, System.currentTimeMillis()).copy(agentStatus = "busy"))
                    }
                },
                onOperationDone = { type, status, id, queueRemaining, _ ->
                    apply {
                        if (type == "chat.prompt" || type == "chat.prompt.remove") {
                            if (isTerminalPromptStatus(status)) state.activeOperations.remove(id)
                            if (status == "already_started") state.activeOperations.add(id)
                            state.remoteQueueCount = queueRemaining
                            persist(state, state.chat.finishPrompt(id, status, System.currentTimeMillis()))
                            if (type == "chat.prompt" && shouldNotifyMonitoredCompletion(
                                    id, state.chat.lastNotifiedOperationId, status, queueRemaining,
                                    state.chat.messages.filter { it.role == MessageRole.User }.mapNotNull { it.operationId },
                                )
                            ) {
                                recordCompletion(state, id, status)
                            }
                        }
                    }
                },
                onStatus = { status, _, queuedCount, operationId, snapshot ->
                    apply {
                        val firstSnapshot = shouldRestoreMonitoredQueue(
                            snapshot, state.snapshotReceived, state.chat.bridgeResyncRequired,
                        )
                        state.remoteQueueCount = queuedCount
                        if (snapshot) {
                            state.snapshotReceived = true
                            state.retryAttempt = 0
                            if (status == "idle") state.activeOperations.clear()
                        }
                        if (status in setOf("busy", "waitingApproval") && operationId.isNotBlank()) {
                            state.activeOperations.add(operationId)
                        }
                        if (status != "idle" || (state.snapshotReceived && state.activeOperations.isEmpty())) {
                            persist(state, state.chat.copy(
                                agentStatus = status,
                                lastSyncAtMillis = System.currentTimeMillis(),
                                connectionError = null,
                            ))
                        }
                        if (firstSnapshot) restoreQueuedOperations(state)
                        // Run after BridgeClient's postApplied has persisted the event checkpoint.
                        handler.post { apply { finishIfIdle(state) } }
                    }
                },
                onSession = { sessionId, resumable, _ ->
                    apply { persist(state, state.chat.bindAcpSession(sessionId, resumable)) }
                },
                onEventGeneration = { generation, reset ->
                    apply {
                        if (reset || (state.chat.bridgeEventGeneration != null && state.chat.bridgeEventGeneration != generation)) {
                            requireForegroundRecovery(state)
                        } else {
                            persist(state, state.chat.copy(bridgeEventGeneration = generation))
                        }
                    }
                },
                onResyncRequired = { apply { requireForegroundRecovery(state) } },
                onEventId = { eventId ->
                    apply {
                        if (!state.chat.bridgeResyncRequired) {
                            persist(state, state.chat.recordBridgeEventId(eventId))
                        }
                    }
                },
                onFailure = { failure ->
                    apply { connectionFailed(state, failure) }
                },
            )
        } catch (_: RuntimeException) {
            warn(state.chat, R.string.monitor_start_error)
            removeMonitor(state)
        }
    }

    private fun restoreQueuedOperations(state: MonitoredChat) {
        if (state.connection?.restoreQueuedPrompts(state.chat) != true) {
            connectionFailed(state, bridgeConnectionFailure())
        }
    }

    private fun connectionFailed(state: MonitoredChat, failure: Throwable) {
        state.connectionEpoch++
        state.connection?.close()
        state.connection = null
        if (failure is BridgeConnectionException && failure.category == BridgeConnectionFailureCategory.Authentication) {
            warn(state.chat, R.string.monitor_auth_error)
            removeMonitor(state)
        } else {
            persist(state, state.chat.copy(connectionError = getString(R.string.monitor_connection_error)))
            val delay = monitorRetryDelayMillis(state.retryAttempt++)
            handler.postDelayed({ if (owns(state)) connect(state) }, delay)
        }
    }

    private fun receiveApproval(state: MonitoredChat, request: BridgeApprovalRequest) {
        if (approvalStore.load().any { it.id == request.approvalId }) return
        val chat = state.chat
        val expired = request.expiresAtMillis?.let { it <= System.currentTimeMillis() } == true
        val approval = request.toApproval(chat, System.currentTimeMillis()).copy(
            status = if (expired) ApprovalStatus.Expired else ApprovalStatus.Pending,
        )
        approvalStore.upsert(approval)
        if (approval.status == ApprovalStatus.Pending) {
            notifications.showApproval(approval)
        }
    }

    private fun finishIfIdle(state: MonitoredChat) {
        val pendingApproval = approvalStore.load().any {
                it.chatId == state.chat.id && it.status.isActionable()
            }
        if (!canFinishMonitoredChat(
                state.snapshotReceived, state.chat.agentStatus, state.activeOperations.size,
                state.remoteQueueCount, state.chat.queuedPrompts.size, pendingApproval,
            )
        ) return
        removeMonitor(state)
    }

    private fun recordCompletion(state: MonitoredChat, operationId: String, status: String) {
        // Apply attention before BridgeClient advances this event's checkpoint, including replay.
        chatStore.setUnread(state.chat.id, true)
        persist(state, state.chat.copy(lastNotifiedOperationId = operationId))
        val preview = if (status == "failed") getString(R.string.monitor_task_failed) else state.chat.messages.lastOrNull {
            it.role == MessageRole.Agent && it.kind == ChatMessageKind.Message
        }?.text?.take(500) ?: getString(R.string.monitor_completion)
        notifications.showCompletion(state.chat.id, state.chat.title, preview)
    }

    private fun requireForegroundRecovery(state: MonitoredChat) {
        persist(state, state.chat.copy(bridgeResyncRequired = true))
        warn(state.chat, R.string.monitor_resync_error)
        removeMonitor(state)
    }

    private fun persist(state: MonitoredChat, chat: Chat) {
        state.chat = chatStore.upsert(chat)
    }

    private fun warn(chat: Chat, messageId: Int) {
        val message = getString(messageId)
        chatStore.upsert(chat.copy(connectionError = message))
        notifications.showMonitorError(chat.id, chat.title, message)
    }

    private fun owns(state: MonitoredChat): Boolean =
        acceptingCallbacks && monitored[state.chat.id] === state

    private fun removeMonitor(state: MonitoredChat) {
        monitored.remove(state.chat.id)
        state.connectionEpoch++
        state.connection?.close()
        state.connection = null
        if (monitored.isEmpty()) finishMonitoring()
    }

    private fun stopWithWarning(messageId: Int) {
        val stopped = monitored.values.toList()
        // Android grants only a short grace period after onTimeout; stop before storage work.
        finishMonitoring()
        stopped.forEach { state ->
            runCatching { warn(state.chat, messageId) }
                .onFailure {
                    runCatching { notifications.showMonitorError(state.chat.id, state.chat.title, getString(messageId)) }
                }
        }
    }

    // Also cancels already posted retries and invalidates callbacks queued by OkHttp.
    private fun releaseOwnership() {
        acceptingCallbacks = false
        loadJob?.cancel()
        loadJob = null
        handler.removeCallbacksAndMessages(null)
        monitored.values.forEach { state ->
            state.connectionEpoch++
            state.connection?.close()
        }
        monitored.clear()
    }

    private fun finishMonitoring() {
        releaseOwnership()
        if (launchRequest?.epoch == ownedRequestEpoch) launchRequest = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopWithWarning(R.string.monitor_timeout)
    }

    override fun onDestroy() {
        releaseOwnership()
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_CHAT_IDS = "monitor.chatIds"
        private const val EXTRA_REQUEST_EPOCH = "monitor.requestEpoch"
        private const val NOTIFICATION_ID = 0x414350
        private const val MAX_RUNTIME_MILLIS = 60 * 60 * 1000L
        private var instance: ChatMonitorService? = null
        private var requestEpoch = 0L
        private var launchRequest: LaunchRequest? = null

        fun start(context: Context, chatIds: Collection<String>): Result<Unit> {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                return Result.failure(IllegalStateException("Monitoring ownership must be transferred on the main thread."))
            }
            if (chatIds.isEmpty()) {
                stop(context)
                return Result.success(Unit)
            }
            val ids = chatIds.toSet()
            return try {
                val request = LaunchRequest(++requestEpoch, ids)
                launchRequest = request
                val intent = Intent(context, ChatMonitorService::class.java)
                    .putStringArrayListExtra(EXTRA_CHAT_IDS, ArrayList(ids))
                    .putExtra(EXTRA_REQUEST_EPOCH, request.epoch)
                checkNotNull(context.startForegroundService(intent))
                Result.success(Unit)
            } catch (_: RuntimeException) {
                launchRequest = null
                persistStartFailure(context, ids)
                Result.failure(IllegalStateException(context.getString(R.string.monitor_start_error)))
            }
        }

        fun stop(context: Context) {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "Monitoring ownership must be transferred on the main thread."
            }
            requestEpoch++
            launchRequest = null
            instance?.releaseOwnership()
            context.stopService(Intent(context, ChatMonitorService::class.java))
        }

        private fun persistStartFailure(context: Context, ids: Set<String>) {
            persistWarning(context, ids, R.string.monitor_start_error)
        }

        private fun persistWarning(context: Context, ids: Set<String>, messageId: Int) {
            CoroutineScope(Dispatchers.IO).launch {
                val message = context.getString(messageId)
                val saved = runCatching {
                    val store = ChatStore(context)
                    val notifications = ChatNotificationManager(context)
                    store.load()
                    val warned = store.recordConnectionErrors(ids, message)
                    store.awaitDurable()
                    warned.forEach {
                        notifications.showMonitorError(it.id, it.title, message)
                    }
                }
                if (saved.isFailure) {
                    runCatching {
                        val notifications = ChatNotificationManager(context)
                        ids.forEach { notifications.showMonitorError(it, context.getString(R.string.app_name), message) }
                    }
                }
            }
        }
    }
}

internal fun monitorRetryDelayMillis(attempt: Int): Long =
    (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)

internal fun shouldNotifyMonitoredCompletion(
    operationId: String,
    lastNotifiedOperationId: String?,
    status: String,
    queueRemaining: Int,
    operationOrder: List<String> = emptyList(),
): Boolean {
    if (operationId.isBlank() || operationId == lastNotifiedOperationId ||
        status !in setOf("completed", "failed") || queueRemaining != 0
    ) return false
    val candidateIndex = operationOrder.lastIndexOf(operationId)
    val notifiedIndex = lastNotifiedOperationId?.let { operationOrder.lastIndexOf(it) } ?: -1
    return candidateIndex < 0 || notifiedIndex < 0 || candidateIndex > notifiedIndex
}

internal fun canFinishMonitoredChat(
    snapshotReceived: Boolean,
    agentStatus: String,
    activeOperationCount: Int,
    remoteQueueCount: Int,
    persistedQueueCount: Int,
    pendingApproval: Boolean,
): Boolean = snapshotReceived && agentStatus in setOf("idle", "failed") && activeOperationCount == 0 &&
    remoteQueueCount == 0 && persistedQueueCount == 0 && !pendingApproval

internal fun shouldRestoreMonitoredQueue(
    snapshot: Boolean,
    snapshotReceived: Boolean,
    resyncRequired: Boolean,
): Boolean = snapshot && !snapshotReceived && !resyncRequired

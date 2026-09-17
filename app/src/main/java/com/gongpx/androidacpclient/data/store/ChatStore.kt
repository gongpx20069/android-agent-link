package com.gongpx.androidacpclient.data.store

import android.content.Context
import android.util.Log
import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.model.ChatMessage
import com.gongpx.androidacpclient.data.model.ChatMessageKind
import com.gongpx.androidacpclient.data.model.Approval
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Process-wide, ordered write-behind repository. UI updates only stage immutable records.
 * Network writes must pass awaitDurable() on an IO thread, including cancellation/reconnect.
 */
class ChatStore private constructor(private val shared: Shared) {
    constructor(context: Context) : this(synchronized(companionLock) {
        instance ?: Shared(ChatDatabase(context.applicationContext)).also { instance = it }
    })
    internal constructor(database: ChatDatabase) : this(Shared(database))
    val failure: StateFlow<String?> get() = shared.failure
    fun readyForEvent(): Boolean = synchronized(shared.lock) {
        shared.checkFailure()
        shared.pendingCharacters + shared.writingCharacters < 4 * 1024 * 1024
    }

    suspend fun loadAsync(): List<Chat> = withContext(Dispatchers.IO) { load() }

    /** Blocking initialization/barrier; never call from the UI thread. */
    fun load(): List<Chat> {
        awaitDurable()
        return snapshot()
    }

    fun snapshot(): List<Chat> = synchronized(shared.lock) {
        shared.checkReady()
        shared.chats.values.toList()
    }

    fun recordConnectionErrors(ids: Set<String>, message: String): List<Chat> = synchronized(shared.lock) {
        shared.checkReady()
        ids.mapNotNull { id -> shared.chats[id]?.let { upsert(it.copy(connectionError = message)) } }
    }

    fun upsert(chat: Chat, prepend: Boolean = false): Chat = synchronized(shared.lock) {
        shared.checkReady()
        val old = shared.chats[chat.id]
        val reset = old != null && old.timelineId != chat.timelineId
        if (reset) shared.pending.remove(chat.id)?.let { shared.pendingCharacters -= it.characters }
        val pending = if (reset) Pending(chat, reset = true).also { shared.pending[chat.id] = it }
            else shared.pending.getOrPut(chat.id) { Pending(chat) }
        val previous = if (reset) emptyMap() else old?.messages.orEmpty().associateBy { it.localId }
        if (prepend) {
            val prefix = chat.messages.filter { it.localId !in previous }.map { it.localId }
            val ordered = prefix + pending.prependIds.filterNot { it in prefix }
            pending.prependIds.clear()
            pending.prependIds.addAll(ordered)
        }
        chat.messages.forEach { message ->
            if (previous[message.localId] !== message && previous[message.localId] != message) {
                val oldMessage = pending.messages.put(message.localId, message)
                val delta = message.weight() - (oldMessage?.weight() ?: 0)
                pending.characters += delta
                shared.pendingCharacters += delta
            }
        }
        pending.chat = chat
        shared.deleted.remove(chat.id)
        val hot = chat.hotWindow()
        shared.chats[chat.id] = hot
        shared.schedule()
        hot
    }

    fun remove(chatId: String) = synchronized(shared.lock) {
        shared.checkReady()
        shared.chats.remove(chatId)
        shared.pending.remove(chatId)?.let { shared.pendingCharacters -= it.characters }
        shared.deleted.add(chatId)
        shared.unread = shared.unread - chatId
        shared.unreadDirty = true
        shared.schedule()
    }

    fun loadUnreadChatIds(): Set<String> = synchronized(shared.lock) {
        shared.checkReady()
        shared.unread
    }

    fun setUnread(chatId: String, unread: Boolean) = synchronized(shared.lock) {
        shared.checkReady()
        val next = if (unread) shared.unread + chatId else shared.unread - chatId
        if (next != shared.unread) {
            shared.unread = next
            shared.unreadDirty = true
            shared.schedule()
        }
    }

    suspend fun older(chat: Chat): List<ChatMessage> = withContext(Dispatchers.IO) {
        awaitDurable()
        shared.worker.submit<List<ChatMessage>> {
            val first = chat.messages.firstOrNull {
                it.kind != ChatMessageKind.CommandUpdate && it.kind != ChatMessageKind.ConfigUpdate
            }
            val before = first?.let { shared.database.position(chat.id, it.localId) } ?: Long.MAX_VALUE
            shared.database.page(chat.id, before).messages
        }.get()
    }

    fun awaitDurable() {
        shared.worker.submit { shared.initialize(); shared.flush() }.get()
        shared.checkFailure()
    }

    internal fun loadApprovals(): List<Approval> = synchronized(shared.lock) {
        shared.checkReady()
        shared.approvals.values.toList()
    }

    internal fun saveApprovals(items: List<Approval>) = synchronized(shared.lock) {
        shared.checkReady()
        val next = items.associateBy { it.id }
        shared.approvals.keys.filterNot { it in next }.forEach { shared.pendingApprovals[it] = null }
        next.forEach { (id, item) ->
            if (shared.approvals[id] != item) shared.pendingApprovals[id] = item
        }
        shared.approvals.clear()
        shared.approvals.putAll(next)
        if (shared.pendingApprovals.isNotEmpty()) shared.schedule()
    }

    /** Keeps event effects and its cursor in the same pending transaction. */
    fun <T> batch(action: () -> T): T = synchronized(shared.lock) {
        shared.checkReady()
        try {
            action()
        } catch (error: Exception) {
            shared.fail(error)
            throw error
        }
    }

    private class Pending(var chat: Chat, val reset: Boolean = false) {
        val messages = linkedMapOf<String, ChatMessage>()
        val prependIds = linkedSetOf<String>()
        var characters = 0L
    }

    internal fun closeForTest() {
        shared.worker.shutdown()
        check(shared.worker.awaitTermination(10, TimeUnit.SECONDS))
        shared.database.close()
    }

    private class Shared(val database: ChatDatabase) {
        val lock = Any()
        val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "chat-storage").apply { isDaemon = true }
        }
        val failure = MutableStateFlow<String?>(null)
        val chats = linkedMapOf<String, Chat>()
        val pending = linkedMapOf<String, Pending>()
        val deleted = linkedSetOf<String>()
        val approvals = linkedMapOf<String, Approval>()
        val pendingApprovals = linkedMapOf<String, Approval?>()
        var unread = emptySet<String>()
        var unreadDirty = false
        var initialized = false
        var scheduled = false
        var pendingCharacters = 0L
        var writingCharacters = 0L

        fun checkFailure() {
            check(failure.value == null) { failure.value.orEmpty() }
        }

        fun checkReady() {
            checkFailure()
            check(initialized) { "Chat storage is not loaded" }
        }

        fun initialize() {
            checkFailure()
            if (initialized) return
            try {
                database.initialize()
                val loaded = database.load()
                val loadedApprovals = database.loadApprovals()
                val savedUnread = JSONArray(database.readState("unread") ?: "[]")
                synchronized(lock) {
                    loaded.forEach { chats[it.id] = it }
                    loadedApprovals.forEach { approvals[it.id] = it }
                    unread = List(savedUnread.length()) { savedUnread.getString(it) }.toSet()
                    initialized = true
                }
            } catch (error: Exception) {
                fail(error)
                throw error
            }
        }

        fun schedule() {
            if (scheduled) return
            scheduled = true
            worker.schedule({ flush() }, 100, TimeUnit.MILLISECONDS)
        }

        fun flush() {
            data class Batch(
                val writes: List<Pending>, val deletes: Set<String>, val unread: Set<String>?,
                val approvals: Map<String, Approval?>,
            )
            val batch = synchronized(lock) {
                if (failure.value != null) return
                val batch = Batch(pending.values.toList(), deleted.toSet(), unread.takeIf { unreadDirty }, pendingApprovals.toMap())
                pending.clear()
                writingCharacters = pendingCharacters
                pendingCharacters = 0
                deleted.clear()
                pendingApprovals.clear()
                unreadDirty = false
                scheduled = false
                batch
            }
            if (batch.writes.isEmpty() && batch.deletes.isEmpty() && batch.unread == null && batch.approvals.isEmpty()) return
            try {
                database.transaction {
                    batch.deletes.forEach(database::remove)
                    batch.writes.forEach {
                        val ordered = it.prependIds.mapNotNull(it.messages::get) +
                            it.messages.values.filterNot { message -> message.localId in it.prependIds }
                        database.writeChat(it.chat, ordered, it.reset, it.prependIds)
                    }
                    batch.unread?.let { database.writeState("unread", JSONArray(it).toString()) }
                    batch.approvals.forEach { (id, item) -> database.writeApproval(id, item) }
                }
            } catch (error: Exception) {
                fail(error)
            } finally {
                synchronized(lock) { writingCharacters = 0 }
            }
        }

        fun fail(error: Exception) {
            // No content, keys or database blobs in logs. Fail closed: no later checkpoint can commit.
            Log.e("ChatStore", "Chat persistence failed (${error.javaClass.simpleName})")
            failure.value = "Chat storage failed. Messages have not been confirmed saved. Restart the app to retry."
        }
    }

    companion object {
        private val companionLock = Any()
        private var instance: Shared? = null
    }
}

private fun ChatMessage.weight(): Long = text.length.toLong() + (details?.length ?: 0)

internal fun Chat.hotWindow(): Chat {
    // Never evict an active turn: late tool updates and operation ordering still need its rows.
    if (agentStatus in setOf("busy", "waitingApproval")) return this
    var characters = 0
    var start = messages.size
    while (start > 0 && messages.size - start < ChatDatabase.HOT_MESSAGE_LIMIT) {
        val message = messages[start - 1]
        val size = message.text.length + (message.details?.length ?: 0)
        if (start < messages.size && characters + size > ChatDatabase.HOT_TEXT_LIMIT) break
        characters += size
        start--
    }
    if (start == 0) return this
    val controls = listOf(ChatMessageKind.CommandUpdate, ChatMessageKind.ConfigUpdate).mapNotNull { kind ->
        messages.take(start).lastOrNull { it.kind == kind }
            ?.takeIf { messages.drop(start).none { row -> row.kind == kind } }
    }
    return copy(messages = controls + messages.drop(start), localHistoryBefore = Long.MAX_VALUE)
}

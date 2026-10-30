package com.gongpx.androidacpclient.data.store

import android.content.Context
import com.gongpx.androidacpclient.data.model.*
import com.gongpx.androidacpclient.data.store.ChatJsonCodec.toJson
import com.gongpx.androidacpclient.data.bridge.retryPendingChatDeletions
import com.gongpx.androidacpclient.data.bridge.BridgeClient
import com.gongpx.androidacpclient.data.bridge.deleteSharedChat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import javax.crypto.KeyGenerator
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ChatStoreTest {
    private lateinit var context: Context
    private lateinit var database: ChatDatabase
    private lateinit var store: ChatStore
    private val cipher = RecordCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        database = ChatDatabase(context, cipher) { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        store = ChatStore(database)
    }

    @After fun cleanup() {
        store.closeForTest()
    }

    private fun chat(id: String = "chat", messages: List<ChatMessage> = emptyList()) =
        Chat(id, "Private title", "machine", "Computer", "workspace", "Project", "C:\\private", "agent", "Agent", 1, messages = messages)

    private fun message(index: Int) = ChatMessage(MessageRole.Agent, "message $index", index.toLong())

    private fun restart() {
        store.closeForTest()
        database = ChatDatabase(context, cipher) { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        store = ChatStore(database)
        store.load()
    }

    @Test fun foregroundIdleAndBackgroundActivitySurviveEncryptedRestart() {
        store.load()
        store.upsert(chat().copy(agentStatus = "idle", backgroundWork = true, scheduledTasks = 2))
        store.awaitDurable()
        restart()
        val restored = store.snapshot().single()
        assertEquals("idle", restored.agentStatus)
        assertTrue(restored.backgroundWork)
        assertEquals(2, restored.scheduledTasks)
    }

    @Test fun offlineDeletionCommitsIntentRemovalUnreadAndApprovalsTogether() = runBlocking {
        store.load()
        val stale = chat().copy(agentStatus = "busy", queuedPrompts = listOf(QueuedPrompt("q", "queued", 1)))
        val approval = Approval("a", stale.id, "Chat", stale.machineId, "Machine", "workspace", "command", "Approve", createdAtMillis = 1)
        store.upsert(stale)
        store.saveApprovals(listOf(approval))
        store.setUnread(stale.id, true)
        store.requestDeletion(stale)
        assertTrue(database.load().isEmpty())
        assertTrue(database.loadApprovals().isEmpty())
        assertEquals(stale.machineId, store.pendingDeletions.value.single().machineId)
        store.saveApprovals(listOf(approval))
        store.setUnread(stale.id, true)
        assertThrows(IllegalStateException::class.java) { store.upsert(stale) }
        store.awaitDurable()
        restart()
        assertTrue(store.snapshot().isEmpty())
        assertTrue(store.loadUnreadChatIds().isEmpty())
        assertTrue(store.loadApprovals().isEmpty())
        assertEquals(stale.id, store.pendingDeletions.value.single().chatId)
        assertThrows(IllegalStateException::class.java) { store.upsert(stale) }
        Unit
    }

    @Test fun failedDeletionTransactionKeepsOriginalChatAndNoRetryIntent() {
        store.load()
        store.upsert(chat())
        store.setUnread("chat", true)
        store.awaitDurable()
        database.writableDatabase.execSQL("CREATE TRIGGER reject_delete_intent BEFORE INSERT ON state WHEN NEW.id='pendingChatDeletions' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertThrows(IllegalStateException::class.java) { runBlocking { store.requestDeletion(chat()) } }
        assertEquals("chat", database.load().single().id)
        assertNull(database.readState("pendingChatDeletions"))
        assertNull(database.readState("removedChats"))
        assertTrue(store.pendingDeletions.value.isEmpty())
        assertNotNull(store.failure.value)
    }

    @Test fun missingMachineNeverRetargetsAndPendingErrorSurvivesRestart() = runBlocking {
        store.load()
        store.upsert(chat())
        store.requestDeletion(chat())
        val other = Machine("other", "Computer", "http://localhost", "token", "f")
        retryPendingChatDeletions(store, listOf(other)) { _, _ -> fail("Must not target another machine") }
        restart()
        assertTrue(store.pendingDeletions.value.single().error!!.contains("missing"))
        store.confirmDeletion(other.id, "chat")
        store.awaitDurable()
        assertEquals("machine", store.pendingDeletions.value.single().machineId)
    }

    @Test fun retryKeepsOfflineAuthBusyUnsupportedAndUnknownOutcomesUntilExactSuccess() = runBlocking {
        store.load()
        store.upsert(chat())
        store.requestDeletion(chat())
        val machine = Machine("machine", "Computer", "http://localhost", "token", "f")
        for (message in listOf("Offline", "Authentication required", "CONFLICT: busy", "UNSUPPORTED", "Wrong chat confirmation")) {
            retryPendingChatDeletions(store, listOf(machine)) { target, id ->
                assertEquals(machine.id, target.id)
                assertEquals("chat", id)
                throw IllegalStateException(message)
            }
            assertEquals(message, store.pendingDeletions.value.single().error)
        }
        restart()
        retryPendingChatDeletions(store, listOf(machine)) { _, _ -> }
        assertTrue(store.pendingDeletions.value.isEmpty())
        restart()
        assertTrue(store.pendingDeletions.value.isEmpty())
        assertTrue(store.isRemoved("chat"))
        assertTrue(store.snapshot().isEmpty())
    }

    @Test fun ownershipCancellationRetainsDurableRequestForNextOwner() = runBlocking {
        store.load()
        store.upsert(chat())
        store.requestDeletion(chat())
        val machine = Machine("machine", "Computer", "http://localhost", "token", "f")
        try {
            retryPendingChatDeletions(store, listOf(machine)) { _, _ -> throw CancellationException("Handoff") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        restart()
        assertEquals("chat", store.pendingDeletions.value.single().chatId)
        retryPendingChatDeletions(store, listOf(machine)) { _, _ -> }
        assertTrue(store.pendingDeletions.value.isEmpty())
    }

    @Test fun serverTombstoneAcknowledgesOnlyOriginalMachineAndNeverResurrects() = runBlocking {
        store.load()
        store.upsert(chat())
        store.requestDeletion(chat())
        store.confirmDeletion("machine", "chat")
        store.awaitDurable()
        restart()
        assertTrue(store.pendingDeletions.value.isEmpty())
        assertTrue(store.isRemoved("chat"))
        assertThrows(IllegalStateException::class.java) { store.upsert(chat()) }
        store.requestDeletion(chat())
        assertTrue(store.pendingDeletions.value.isEmpty())
    }

    @Test fun onlineDeletionRetriesConflictAndValidatesExactWireConfirmationWithoutOtherActions() = runBlocking {
        store.load()
        store.upsert(chat())
        store.requestDeletion(chat())
        val server = MockWebServer()
        val frames = mutableListOf<JSONObject>()
        listOf(
            JSONObject().put("status", "error").put("code", "CONFLICT").put("message", "Chat is busy"),
            JSONObject().put("status", "ok").put("data", JSONObject().put("chatId", "wrong").put("deleted", true)),
            JSONObject().put("status", "ok").put("data", JSONObject().put("chatId", "chat").put("deleted", true)),
        ).forEach { response ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val request = JSONObject(text)
                    frames.add(request)
                    webSocket.send(response.put("type", "control.result").put("requestId", request.getString("requestId")).toString())
                    webSocket.send("""{"type":"bridge.done"}""")
                }
            }))
        }
        server.start()
        try {
            val machine = Machine("machine", "Computer", server.url("/").toString(), "token", "f")
            val bridge = BridgeClient()
            retryPendingChatDeletions(store, listOf(machine), bridge::deleteSharedChat)
            assertEquals("Chat is busy", store.pendingDeletions.value.single().error)
            restart()
            retryPendingChatDeletions(store, listOf(machine), bridge::deleteSharedChat)
            assertTrue(store.pendingDeletions.value.single().error!!.contains("confirm"))
            retryPendingChatDeletions(store, listOf(machine), bridge::deleteSharedChat)
            assertTrue(store.pendingDeletions.value.isEmpty())
            assertTrue(frames.all { it.getString("action") == "chat.delete" && it.getString("chatId") == "chat" })
            assertEquals(3, frames.size)
        } finally {
            server.shutdown()
        }
    }

    @Test fun renamedTitleAndRevisionSurviveEncryptedStoreRestart() {
        store.load()
        store.upsert(chat(messages = listOf(message(1))).copy(
            title = "Renamed", titleRevision = 7, titleGeneration = "server"))
        store.awaitDurable()
        store.closeForTest()
        database = ChatDatabase(context, cipher) { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        store = ChatStore(database)
        val loaded = store.load().single()
        assertEquals("Renamed", loaded.title)
        assertEquals(7L, loaded.titleRevision)
        assertEquals("server", loaded.titleGeneration)
        assertEquals("message 1", loaded.messages.single().text)
    }

    @Test fun sharedDeletionRejectsLateWritesAndPersistsAcrossRestart() {
        store.load()
        val stale = chat(messages = listOf(message(1)))
        store.upsert(stale)
        store.remove(stale.id)
        assertTrue(store.isRemoved(stale.id))
        assertThrows(IllegalStateException::class.java) { store.upsert(stale) }
        store.awaitDurable()
        store.closeForTest()
        database = ChatDatabase(context, cipher) { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        store = ChatStore(database)
        assertTrue(store.load().isEmpty())
        assertTrue(store.isRemoved(stale.id))
        assertThrows(IllegalStateException::class.java) { store.upsert(stale) }
        store.upsert(chat("new-session-chat"))
        assertEquals("new-session-chat", store.snapshot().single().id)
    }

    private fun blob(table: String, id: String): ByteArray =
        database.readableDatabase.rawQuery("SELECT data FROM $table WHERE id=?", arrayOf(id)).use {
            assertTrue(it.moveToFirst())
            it.getBlob(0)
        }

    private fun count(): Int = database.readableDatabase.rawQuery("SELECT COUNT(*) FROM messages", null).use {
        it.moveToFirst(); it.getInt(0)
    }

    @Test fun migratesAllHistoryThenLoadsOnlyAWindow() {
        val legacy = chat(messages = (0 until 1000).map(::message)).copy(lastBridgeEventId = 42, bridgeEventGeneration = "generation")
        context.getSharedPreferences("chats", 0).edit()
            .putString("chats", JSONArray(listOf(legacy.toJson())).toString())
            .putStringSet("unread_chat_ids", setOf("chat")).commit()
        val loaded = store.load().single()
        assertEquals(42, loaded.lastBridgeEventId)
        assertEquals("generation", loaded.bridgeEventGeneration)
        assertEquals(200, loaded.messages.size)
        assertEquals("message 800", loaded.messages.first().text)
        assertEquals(1000, count())
        assertNotNull(loaded.localHistoryBefore)
        assertEquals(setOf("chat"), store.loadUnreadChatIds())
        assertFalse(context.getSharedPreferences("chats", 0).contains("chats"))
        assertFalse(String(blob("chats", "chat"), Charsets.UTF_8).contains("Private title"))
    }

    @Test fun changingOneMessageDoesNotRewriteOtherChatsOrMessages() {
        store.load()
        val first = chat(messages = (0..10).map(::message))
        val other = chat("other", listOf(message(20)))
        store.upsert(first)
        store.upsert(other)
        store.awaitDurable()
        val otherBefore = blob("chats", other.id)
        val unchanged = blob("messages", first.messages.first().localId)
        val changedBefore = blob("messages", first.messages.last().localId)
        store.batch {
            store.upsert(first.copy(
                messages = first.messages.dropLast(1) + first.messages.last().copy(text = "changed"),
                lastBridgeEventId = 99,
            ))
        }
        store.awaitDurable()
        assertArrayEquals(otherBefore, blob("chats", other.id))
        assertArrayEquals(unchanged, blob("messages", first.messages.first().localId))
        assertFalse(changedBefore.contentEquals(blob("messages", first.messages.last().localId)))
        assertEquals(99, database.load().first { it.id == "chat" }.lastBridgeEventId)
        assertEquals(12, count())
    }

    @Test fun checkpointOnlyUpdateWritesNoMessageBlobs() {
        store.load()
        val chat = chat(messages = listOf(message(1)))
        store.upsert(chat)
        store.awaitDurable()
        val before = blob("messages", chat.messages.single().localId)
        store.upsert(chat.copy(lastBridgeEventId = 123))
        store.awaitDurable()
        assertArrayEquals(before, blob("messages", chat.messages.single().localId))
    }

    @Test fun thousandsOfUpdatesRemainBoundedWithoutLosingArchivedRows() {
        store.load()
        var current = chat()
        repeat(2500) { index ->
            current = store.upsert(current.copy(messages = current.messages + message(index), lastBridgeEventId = index + 1))
            assertTrue(current.messages.size <= 200)
        }
        store.awaitDurable()
        assertEquals(2500, count())
        assertEquals(2500, database.load().single().lastBridgeEventId)
        val texts = mutableListOf<String>()
        var before = Long.MAX_VALUE
        do {
            val page = database.page("chat", before)
            texts.addAll(page.messages.map { it.text })
            before = page.before ?: break
        } while (true)
        assertEquals(2500, texts.toSet().size)
        assertEquals((0 until 2500).map { "message $it" }.toSet(), texts.toSet())
    }

    @Test fun prependAndTimelineReplacementAreExplicit() {
        store.load()
        val initial = chat(messages = listOf(message(5), message(6)))
        store.upsert(initial)
        store.awaitDurable()
        val older = listOf(message(3), message(4))
        store.upsert(initial.copy(messages = older + initial.messages), prepend = true)
        store.awaitDurable()
        assertEquals(listOf("message 3", "message 4", "message 5", "message 6"), database.load().single().messages.map { it.text })
        store.upsert(initial.copy(timelineId = "new-session", messages = listOf(message(9))))
        store.awaitDurable()
        assertEquals(listOf("message 9"), database.load().single().messages.map { it.text })
    }

    @Test fun failedTransactionCannotCommitItsCursorOrApproval() {
        store.load()
        val initial = chat(messages = listOf(message(1)))
        store.upsert(initial)
        store.awaitDurable()
        database.writableDatabase.execSQL("CREATE TRIGGER reject_approval BEFORE INSERT ON approvals BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        val approval = Approval("approval", "chat", "Chat", "machine", "Machine", "workspace", "command", "Approve", createdAtMillis = 1)
        store.batch {
            store.saveApprovals(listOf(approval))
            store.upsert(initial.copy(lastBridgeEventId = 22, messages = initial.messages + message(2)))
        }
        assertThrows(IllegalStateException::class.java) { store.awaitDurable() }
        assertNotNull(store.failure.value)
        assertEquals(0, database.load().single().lastBridgeEventId)
        assertEquals(1, count())
        assertTrue(database.loadApprovals().isEmpty())
        assertThrows(IllegalStateException::class.java) { store.upsert(initial.copy(lastBridgeEventId = 99)) }
    }

    @Test fun cancellationTombstoneAndApprovalsAreDurableBeforeBarrierReturns() {
        store.load()
        val pending = chat().copy(queuedPrompts = listOf(QueuedPrompt("op", "private prompt", 1, removing = true)))
        val approval = Approval("approval", "chat", "Chat", "machine", "Machine", "workspace", "command", "Approve", createdAtMillis = 1)
        store.batch {
            store.upsert(pending)
            store.saveApprovals(listOf(approval))
        }
        store.awaitDurable()
        assertTrue(database.load().single().queuedPrompts.single().removing)
        assertEquals(approval, database.loadApprovals().single())
    }

    @Test fun deletedChatsCannotReturnFromPendingWrites() {
        store.load()
        store.upsert(chat(messages = listOf(message(1))))
        store.remove("chat")
        store.awaitDurable()
        assertTrue(database.load().isEmpty())
        assertEquals(0, count())
    }

    @Test fun largeToolRecordSurvivesChunkingAndDetectsMissingParts() {
        store.load()
        val tool = message(1).copy(kind = ChatMessageKind.Activity, details = "x".repeat(3 * 1024 * 1024))
        store.upsert(chat(messages = listOf(tool)))
        store.awaitDurable()
        assertEquals(tool.details, database.load().single().messages.single().details)
        assertEquals(0, blob("messages", tool.localId).size)
        database.writableDatabase.delete("record_parts", "part=?", arrayOf("1"))
        assertThrows(IllegalStateException::class.java) { database.load() }
    }

    @Test fun unfinishedTurnIsNotEvictedAcrossReload() {
        store.load()
        val rows = (-2..0).map { message(it).copy(role = MessageRole.User) } + (1..400).map(::message)
        store.upsert(chat(messages = rows).copy(agentStatus = "busy"))
        store.awaitDurable()
        assertEquals(rows, database.load().single().messages)
        store.upsert(store.load().single().copy(agentStatus = "idle"))
        store.awaitDurable()
        assertEquals(200, store.load().single().messages.size)
        assertEquals(403, count())
    }

    @Test fun coalescedPrependPagesRetainChronologicalOrder() {
        store.load()
        var current = chat(messages = listOf(message(5), message(6)))
        store.upsert(current)
        store.awaitDurable()
        store.batch {
            current = store.upsert(current.copy(messages = listOf(message(3), message(4)) + current.messages), prepend = true)
            current = store.upsert(current.copy(messages = listOf(message(1), message(2)) + current.messages), prepend = true)
        }
        store.awaitDurable()
        assertEquals((1..6).map { "message $it" }, database.load().single().messages.map { it.text })
    }

    @Test fun failedMigrationLeavesLegacyDataAndNoCommittedMarker() {
        val legacy = chat(messages = listOf(message(1)))
        val raw = JSONArray(listOf(legacy.toJson())).toString()
        context.getSharedPreferences("chats", 0).edit().putString("chats", raw).commit()
        database.writableDatabase.execSQL("CREATE TRIGGER reject_chat BEFORE INSERT ON chats BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertThrows(Exception::class.java) { store.load() }
        assertEquals(raw, context.getSharedPreferences("chats", 0).getString("chats", null))
        assertNull(database.readState("migrated"))
        assertEquals(0, count())
    }
}

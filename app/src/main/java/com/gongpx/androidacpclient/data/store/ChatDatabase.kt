package com.gongpx.androidacpclient.data.store

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.model.ChatMessage
import com.gongpx.androidacpclient.data.model.ChatMessageKind
import com.gongpx.androidacpclient.data.model.Approval
import com.gongpx.androidacpclient.data.store.ChatJsonCodec.toChat
import com.gongpx.androidacpclient.data.store.ChatJsonCodec.toChatMessage
import com.gongpx.androidacpclient.data.store.ChatJsonCodec.toJson
import java.security.KeyStore
import javax.crypto.SecretKey
import org.json.JSONArray
import org.json.JSONObject

/** Only accessed by ChatStore's single IO worker. Content and tool details never enter SQL. */
internal class ChatDatabase(
    private val context: Context,
    private val providedCipher: RecordCipher? = null,
    private val legacyPreferences: (String) -> SharedPreferences = { name ->
        EncryptedSharedPreferences.create(
            context, name,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    },
) : SQLiteOpenHelper(context, "chat-records.db", null, 1) {
    private val cipher by lazy {
        if (providedCipher != null) return@lazy providedCipher
        MasterKey.Builder(context, "agentlink-chat-records")
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        RecordCipher(keyStore.getKey("agentlink-chat-records", null) as SecretKey)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chats (id TEXT PRIMARY KEY, data BLOB NOT NULL)")
        db.execSQL("CREATE TABLE messages (chat TEXT NOT NULL, id TEXT NOT NULL, position INTEGER NOT NULL, kind TEXT NOT NULL, role TEXT NOT NULL, data BLOB NOT NULL, PRIMARY KEY(chat,id))")
        db.execSQL("CREATE INDEX timeline ON messages(chat,position)")
        db.execSQL("CREATE INDEX controls ON messages(chat,kind,position)")
        db.execSQL("CREATE INDEX message_roles ON messages(chat,role,position)")
        db.execSQL("CREATE TABLE state (id TEXT PRIMARY KEY, data BLOB NOT NULL)")
        db.execSQL("CREATE TABLE approvals (id TEXT PRIMARY KEY, data BLOB NOT NULL)")
        db.execSQL("CREATE TABLE record_parts (identity TEXT NOT NULL, part INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(identity,part))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported chat database upgrade: $oldVersion -> $newVersion")
    }

    fun initialize() {
        writableDatabase
        // The marker and imported rows commit together. An interrupted import leaves legacy data intact.
        if (readState("migrated") == null) {
            val legacy = legacyPreferences("chats")
            val array = JSONArray(legacy.getString("chats", "[]") ?: "[]")
            val legacyApprovals = legacyPreferences("approvals")
            val approvals = ApprovalJsonCodec.decode(legacyApprovals.getString("items", "[]") ?: "[]")
            transaction {
                repeat(array.length()) { index ->
                    val chat = array.getJSONObject(index).toChat()
                    writeChat(chat, chat.messages, reset = true)
                }
                writeState("unread", JSONArray(legacy.getStringSet("unread_chat_ids", emptySet()).orEmpty()).toString())
                writeState("migrated", "1")
                approvals.forEach { writeApproval(it.id, it) }
            }
            check(legacy.edit().remove("chats").remove("unread_chat_ids").commit()) {
                "Could not remove migrated legacy chat data"
            }
            check(legacyApprovals.edit().remove("items").commit()) { "Could not remove migrated approvals" }
        }
    }

    fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val result = block()
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    fun load(): List<Chat> = readableDatabase.rawQuery("SELECT id,data FROM chats", null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val chat = JSONObject(decodeRecord("chat:$id", cursor.getBlob(1))).toChat()
                val page = page(id)
                val messages = if (chat.agentStatus in setOf("busy", "waitingApproval")) {
                    // Keep unfinished-turn rows available for late tool deltas after process restart.
                    val turnStart = readableDatabase.rawQuery(
                        "SELECT position FROM messages WHERE chat=? AND role<>'User' AND position < " +
                            "(SELECT position FROM messages WHERE chat=? AND role='User' ORDER BY position DESC LIMIT 1) " +
                            "ORDER BY position DESC LIMIT 1",
                        arrayOf(id, id),
                    ).use { if (it.moveToFirst()) it.getLong(0) + 1 else Long.MIN_VALUE }
                    val active = readableDatabase.rawQuery(
                        "SELECT id,data FROM messages WHERE chat=? AND position>=? ORDER BY position",
                        arrayOf(id, turnStart.toString()),
                    ).use { rows ->
                        buildList {
                            while (rows.moveToNext()) add(JSONObject(decodeRecord(
                                "message:$id:${rows.getString(0)}", rows.getBlob(1),
                            )).toChatMessage())
                        }
                    }
                    val activeIds = active.map { it.localId }.toSet()
                    page.messages.filterNot { it.localId in activeIds } + active
                } else page.messages
                add(chat.copy(messages = messages, localHistoryBefore = page.before))
            }
        }
    }

    data class Page(val messages: List<ChatMessage>, val before: Long?)

    fun position(chatId: String, messageId: String): Long? = readableDatabase.rawQuery(
        "SELECT position FROM messages WHERE chat=? AND id=?", arrayOf(chatId, messageId),
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    fun page(chatId: String, before: Long = Long.MAX_VALUE): Page {
        val messages = mutableListOf<ChatMessage>()
        var first = before
        var bytes = 0
        readableDatabase.rawQuery(
            "SELECT id,position,data FROM messages WHERE chat=? AND position<? ORDER BY position DESC LIMIT ?",
            arrayOf(chatId, before.toString(), (HOT_MESSAGE_LIMIT + 1).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (messages.size >= HOT_MESSAGE_LIMIT || (messages.isNotEmpty() && bytes >= HOT_TEXT_LIMIT)) break
                val id = cursor.getString(0)
                val text = decodeRecord("message:$chatId:$id", cursor.getBlob(2))
                if (messages.isNotEmpty() && bytes.toLong() + text.length > HOT_TEXT_LIMIT) break
                bytes += text.length
                messages.add(JSONObject(text).toChatMessage())
                first = cursor.getLong(1)
            }
        }
        messages.reverse()
        val hasOlder = readableDatabase.rawQuery(
            "SELECT 1 FROM messages WHERE chat=? AND position<? LIMIT 1", arrayOf(chatId, first.toString()),
        ).use { it.moveToFirst() }
        // Controls are current state, even when their timeline position is outside the loaded page.
        if (before == Long.MAX_VALUE) {
            listOf(ChatMessageKind.CommandUpdate, ChatMessageKind.ConfigUpdate).forEach { kind ->
                readableDatabase.rawQuery(
                    "SELECT id,data FROM messages WHERE chat=? AND kind=? ORDER BY position DESC LIMIT 1",
                    arrayOf(chatId, kind.name),
                ).use { cursor ->
                    if (cursor.moveToFirst() && messages.none { it.localId == cursor.getString(0) }) {
                        messages.add(0, JSONObject(decodeRecord(
                            "message:$chatId:${cursor.getString(0)}", cursor.getBlob(1),
                        )).toChatMessage())
                    }
                }
            }
        }
        return Page(messages, first.takeIf { hasOlder })
    }

    fun writeChat(chat: Chat, changed: List<ChatMessage>, reset: Boolean, prependIds: Set<String> = emptySet()) {
        val db = writableDatabase
        if (reset) removeMessages(chat.id)
        fun edge(descending: Boolean, default: Long): Long =
            if (changed.isEmpty()) default else db.rawQuery(
                "SELECT position FROM messages WHERE chat=? ORDER BY position ${if (descending) "DESC" else "ASC"} LIMIT 1",
                arrayOf(chat.id),
            ).use { if (it.moveToFirst()) it.getLong(0) else default }
        val bounds = edge(false, 0) to edge(true, -1)
        var next = bounds.second + 1
        val existing = changed.associate { message ->
            message.localId to db.rawQuery(
                "SELECT position FROM messages WHERE chat=? AND id=?", arrayOf(chat.id, message.localId),
            ).use { if (it.moveToFirst()) it.getLong(0) else null }
        }
        var previous = bounds.first - prependIds.count { existing[it] == null }
        changed.forEach { message ->
            val position = existing[message.localId] ?: if (message.localId in prependIds) previous++ else next++
            check(db.insertWithOnConflict("messages", null, ContentValues().apply {
                put("chat", chat.id)
                put("id", message.localId)
                put("position", position)
                put("kind", message.kind.name)
                put("role", message.role.name)
                put("data", encodeRecord("message:${chat.id}:${message.localId}", message.toJson().toString()))
            }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Could not persist message" }
        }
        db.insertWithOnConflict("chats", null, ContentValues().apply {
            put("id", chat.id)
            put("data", encodeRecord("chat:${chat.id}", chat.copy(messages = emptyList()).toJson().toString()))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Could not persist chat" } }
    }

    fun remove(id: String) {
        removeMessages(id)
        writableDatabase.delete("chats", "id=?", arrayOf(id))
        writableDatabase.delete("record_parts", "identity=?", arrayOf("chat:$id"))
    }

    private fun removeMessages(id: String) {
        writableDatabase.rawQuery("SELECT id FROM messages WHERE chat=?", arrayOf(id)).use { cursor ->
            while (cursor.moveToNext()) {
                writableDatabase.delete("record_parts", "identity=?", arrayOf("message:$id:${cursor.getString(0)}"))
            }
        }
        writableDatabase.delete("messages", "chat=?", arrayOf(id))
    }

    fun loadApprovals(): List<Approval> = readableDatabase.rawQuery("SELECT id,data FROM approvals", null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                addAll(ApprovalJsonCodec.decode(decodeRecord("approval:${cursor.getString(0)}", cursor.getBlob(1))))
            }
        }
    }

    fun writeApproval(id: String, item: Approval?) {
        if (item == null) {
            writableDatabase.delete("approvals", "id=?", arrayOf(id))
            writableDatabase.delete("record_parts", "identity=?", arrayOf("approval:$id"))
        } else {
            check(writableDatabase.insertWithOnConflict("approvals", null, ContentValues().apply {
                put("id", id)
                put("data", encodeRecord("approval:$id", ApprovalJsonCodec.encode(listOf(item))))
            }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Could not persist approval" }
        }
    }
    fun writeState(id: String, value: String) {
        check(writableDatabase.insertWithOnConflict("state", null, ContentValues().apply {
            put("id", id)
            put("data", encodeRecord("state:$id", value))
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Could not persist chat state" }
    }

    fun readState(id: String): String? =
        readableDatabase.rawQuery("SELECT data FROM state WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) decodeRecord("state:$id", it.getBlob(0)) else null
        }

    private fun encodeRecord(identity: String, value: String): ByteArray {
        val encrypted = cipher.encrypt(identity, value)
        writableDatabase.delete("record_parts", "identity=?", arrayOf(identity))
        if (encrypted.size <= 64 * 1024) return encrypted
        var offset = 0
        var part = 0
        while (offset < encrypted.size) {
            val end = minOf(offset + 64 * 1024, encrypted.size)
            writableDatabase.insertOrThrow("record_parts", null, ContentValues().apply {
                put("identity", identity)
                put("part", part++)
                put("data", encrypted.copyOfRange(offset, end))
            })
            offset = end
        }
        // Avoid CursorWindow's per-row limit for large tool output. GCM authenticates all parts together.
        return byteArrayOf()
    }

    private fun decodeRecord(identity: String, stored: ByteArray): String {
        if (stored.isNotEmpty()) return cipher.decrypt(identity, stored)
        val bytes = java.io.ByteArrayOutputStream()
        readableDatabase.rawQuery("SELECT part,data FROM record_parts WHERE identity=? ORDER BY part", arrayOf(identity)).use { cursor ->
            var expected = 0
            while (cursor.moveToNext()) {
                check(cursor.getInt(0) == expected++) { "Missing encrypted record part" }
                bytes.write(cursor.getBlob(1))
            }
        }
        return cipher.decrypt(identity, bytes.toByteArray())
    }

    companion object {
        const val HOT_MESSAGE_LIMIT = 200
        const val HOT_TEXT_LIMIT = 512 * 1024
    }
}

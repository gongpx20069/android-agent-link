package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.ChatMessage
import com.gongpx.androidacpclient.data.model.ChatMessageKind

/**
 * Bounded producer/consumer bridge. Only adjacent text deltas are joined; control events
 * stay ordered. A full queue backpressures the socket reader, never the main thread.
 */
internal class ChatEventPump(
    private val schedule: (() -> Unit) -> Unit,
    private val apply: (() -> Unit) -> Unit,
    private val checkpoint: (Int) -> Unit,
    private val deliver: (ChatMessage, Boolean) -> Unit,
    private val failed: (Throwable) -> Unit,
    private val ready: () -> Boolean = { true },
) {
    private class Entry(
        var eventId: Int,
        val replay: Boolean,
        var message: ChatMessage?,
        val action: (() -> Unit)?,
        val bytes: Int,
    ) {
        var text: StringBuilder? = null
        var weight = bytes
    }
    private val lock = Object()
    private val queue = ArrayDeque<Entry>()
    private var weight = 0
    private var scheduled = false
    private var closed = false

    fun event(eventId: Int, bytes: Int, action: () -> Unit = {}) =
        enqueue(Entry(eventId, false, null, action, bytes))

    fun message(eventId: Int, replay: Boolean, message: ChatMessage, bytes: Int) =
        enqueue(Entry(eventId, replay, message, null, bytes))

    private fun enqueue(entry: Entry) {
        synchronized(lock) {
            while (!closed && queue.isNotEmpty() && (queue.size >= 256 || weight + entry.weight > 2 * 1024 * 1024)) {
                lock.wait()
            }
            if (closed) return
            val last = queue.lastOrNull()
            val incoming = entry.message
            val previous = last?.message
            val matching = last != null && previous != null && incoming != null &&
                previous.activityId != null && previous.activityId == incoming.activityId &&
                previous.operationId == incoming.operationId && previous.role == incoming.role &&
                last.replay == entry.replay && last.eventId >= 0 && entry.eventId > last.eventId
            val thought = previous?.kind == ChatMessageKind.Activity && incoming?.kind == ChatMessageKind.Activity &&
                previous.title == "Thought" && incoming.title == "Thought"
            if (matching &&
                previous.isToolSnapshot && incoming.isToolSnapshot
            ) {
                weight -= last.weight
                last.message = incoming
                last.eventId = entry.eventId
                last.weight = entry.weight
            } else if (matching &&
                (thought || (previous.kind == ChatMessageKind.Message && incoming.kind == ChatMessageKind.Message))
            ) {
                val initial = if (thought) previous.details.orEmpty() else previous.text
                val text = last.text ?: StringBuilder(initial).also { last.text = it }
                text.append(if (thought) incoming.details.orEmpty() else incoming.text)
                last.eventId = entry.eventId
                last.weight += entry.weight
            } else {
                queue.addLast(entry)
            }
            weight += entry.weight
            if (!scheduled) {
                scheduled = true
                schedule(::drain)
            }
        }
    }

    private fun drain() {
        val deadline = System.nanoTime() + 4_000_000
        try {
            do {
                if (!ready()) break
                val entry = synchronized(lock) {
                    if (closed || queue.isEmpty()) null else queue.removeFirst().also {
                        weight -= it.weight
                        lock.notifyAll()
                    }
                } ?: break
                apply {
                    val message = entry.message
                    if (message != null) deliver(
                        entry.text?.let {
                            if (message.kind == ChatMessageKind.Activity) message.copy(details = it.toString())
                            else message.copy(text = it.toString())
                        } ?: message, entry.replay,
                    ) else entry.action?.invoke()
                    if (!isClosed() && entry.eventId >= 0) checkpoint(entry.eventId)
                }
            } while (System.nanoTime() < deadline)
        } catch (error: Exception) {
            close()
            failed(error)
        }
        synchronized(lock) {
            if (!closed && queue.isNotEmpty()) schedule(::drain) else scheduled = false
        }
    }

    private fun isClosed() = synchronized(lock) { closed }

    fun close() = synchronized(lock) {
        closed = true
        queue.clear()
        weight = 0
        lock.notifyAll()
    }
}

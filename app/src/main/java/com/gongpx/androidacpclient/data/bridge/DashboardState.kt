package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.model.Machine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

internal const val DASHBOARD_REFRESH_MILLIS = 180_000L

internal suspend fun pollDashboard(
    refresh: suspend () -> Unit,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
    pause: suspend (Long) -> Unit = { delay(it) },
) {
    while (true) {
        currentCoroutineContext().ensureActive()
        val started = now()
        refresh()
        pause((DASHBOARD_REFRESH_MILLIS - (now() - started)).coerceAtLeast(0))
    }
}

internal data class DashboardChat(
    val chat: Chat,
    val revision: Long,
    val generation: String?,
    val changed: Boolean = false,
    val stale: Boolean = false,
)

internal data class DashboardSnapshot(
    val chats: List<DashboardChat> = emptyList(),
    val failedMachines: Set<String> = emptySet(),
    val refreshedAt: Long = 0,
)

/** One read-only catalog request per machine (including pagination), committed as one UI snapshot. */
internal suspend fun refreshDashboard(
    machines: List<Machine>,
    localChats: List<Chat>,
    previous: DashboardSnapshot,
    removed: (String) -> Boolean,
    fetch: suspend (Machine) -> List<JSONObject>,
): DashboardSnapshot = coroutineScope {
    val limit = Semaphore(4)
    val results = machines.map { machine ->
        async {
            limit.withPermit {
                try {
                    val rows = fetch(machine).filterNot { it.optBoolean("deleted") || removed(it.getString("chatId")) }
                    machine to Result.success(rows.map { row ->
                        val chat = mergeSharedChat(machine, row, localChats.firstOrNull { it.id == row.getString("chatId") })
                        DashboardChat(chat, row.optLong("revision"), row.optString("eventGeneration").ifBlank { null })
                    })
                } catch (error: Exception) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    machine to Result.failure<List<DashboardChat>>(error)
                }
            }
        }
    }.awaitAll()
    val old = previous.chats.associateBy { it.chat.machineId to it.chat.id }
    val items = results.flatMap { (machine, result) ->
        result.fold(
            onSuccess = { rows ->
                rows.map { row ->
                    val before = old[machine.id to row.chat.id]
                    row.copy(changed = before?.changed == true || (previous.refreshedAt != 0L &&
                        (before == null || before.revision != row.revision || before.generation != row.generation ||
                            before.chat.title != row.chat.title || before.chat.agentStatus != row.chat.agentStatus ||
                            before.chat.backgroundWork != row.chat.backgroundWork || before.chat.scheduledTasks != row.chat.scheduledTasks)))
                }
            },
            onFailure = {
                val cached = previous.chats.filter { it.chat.machineId == machine.id }
                (if (cached.isNotEmpty()) cached else localChats.filter { it.machineId == machine.id }
                    .map { DashboardChat(it, 0, null) }).filterNot { removed(it.chat.id) }.map { it.copy(stale = true) }
            },
        )
    }
    DashboardSnapshot(items, results.filter { it.second.isFailure }.map { it.first.id }.toSet(), System.currentTimeMillis())
}

package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.store.ChatStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// Foreground and bounded background owners share the outbox, not concurrent deletion requests.
private val deletionSyncMutex = Mutex()

suspend fun retryPendingChatDeletions(
    store: ChatStore,
    machines: List<Machine>,
    delete: suspend (Machine, String) -> Unit,
) = deletionSyncMutex.withLock {
    withContext(Dispatchers.IO) { store.awaitDurable() }
    for (pending in store.pendingDeletions.value) {
        val machine = machines.firstOrNull { it.id == pending.machineId }
        if (machine == null) {
            store.recordDeletionError(pending, "Original computer is missing. Re-pair the same computer to retry.")
        } else {
            try {
                withTimeout(15_000) { delete(machine, pending.chatId) }
                store.confirmDeletion(pending.machineId, pending.chatId)
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                store.recordDeletionError(pending, error.message ?: "Server deletion is not confirmed. Check the connection and retry.")
            }
        }
        withContext(Dispatchers.IO) { store.awaitDurable() }
    }
}

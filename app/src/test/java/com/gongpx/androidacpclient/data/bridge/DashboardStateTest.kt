package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.Machine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DashboardStateTest {
    private val machine = Machine("m", "Computer", "ws://localhost", "token", "fingerprint")
    private fun row(id: String = "chat", revision: Int = 1, status: String = "idle") =
        JSONObject().put("chatId", id).put("chatTitle", id).put("workspaceId", "w").put("workspacePath", "C:\\repo")
            .put("agentId", "copilot-cli").put("status", status).put("revision", revision).put("eventGeneration", "server")

    @Test fun pollingRunsImmediatelyThenEveryThreeMinutesWithoutOverlappingAndStopsOnCancellation() = runBlocking {
        var clock = 0L
        val starts = mutableListOf<Long>()
        val waits = mutableListOf<Long>()
        try {
            pollDashboard(
                refresh = { starts.add(clock); clock += 700 },
                now = { clock },
                pause = {
                    waits.add(it)
                    clock += it
                    if (waits.size == 3) throw CancellationException()
                },
            )
        } catch (_: CancellationException) {
            // Same cancellation path used when leaving the Dashboard or backgrounding the app.
        }
        assertEquals(listOf(0L, 180_000L, 360_000L), starts)
        assertEquals(listOf(179_300L, 179_300L, 179_300L), waits)
    }

    @Test fun unchangedPollIsQuietButOutputAndNewChatsRemainHighlightedUntilAcknowledged() = runBlocking {
        var state = refreshDashboard(listOf(machine), emptyList(), DashboardSnapshot(), { false }) { listOf(row()) }
        assertFalse(state.chats.single().changed)
        state = refreshDashboard(listOf(machine), emptyList(), state, { false }) { listOf(row()) }
        assertFalse(state.chats.single().changed)
        state = refreshDashboard(listOf(machine), emptyList(), state, { false }) { listOf(row(revision = 2), row("new")) }
        assertTrue(state.chats.all { it.changed })
        state = refreshDashboard(listOf(machine), emptyList(), state, { false }) { listOf(row(revision = 2), row("new")) }
        assertTrue(state.chats.all { it.changed })
        state = state.copy(chats = state.chats.map { it.copy(changed = false) })
        state = refreshDashboard(listOf(machine), emptyList(), state, { false }) { listOf(row(revision = 2), row("new")) }
        assertTrue(state.chats.none { it.changed })
        state = refreshDashboard(listOf(machine), emptyList(), state, { false }) {
            listOf(row(revision = 2).put("eventGeneration", "restarted"))
        }
        assertTrue(state.chats.single().changed)
    }

    @Test fun offlineMachineDoesNotHideHealthyMachineOrPretendCachedStateIsFresh() = runBlocking {
        val first = refreshDashboard(listOf(machine), emptyList(), DashboardSnapshot(), { false }) { listOf(row(status = "busy")) }
        val next = refreshDashboard(listOf(machine, machine.copy(id = "healthy")), emptyList(), first, { false }) {
            if (it.id == "m") throw java.io.IOException("offline")
            listOf(row("healthy-chat"))
        }
        assertEquals(setOf("m"), next.failedMachines)
        assertTrue(next.chats.first { it.chat.machineId == "m" }.stale)
        assertEquals("busy", next.chats.first { it.chat.machineId == "m" }.chat.agentStatus)
        assertFalse(next.chats.first { it.chat.machineId == "healthy" }.stale)
    }

    @Test fun refreshChecksEveryMachineOnceWithAtMostFourConcurrentRequests() = runBlocking {
        var active = 0
        var peak = 0
        val called = mutableListOf<String>()
        val machines = (1..9).map { machine.copy(id = "m$it") }
        val snapshot = refreshDashboard(machines, emptyList(), DashboardSnapshot(), { false }) {
            called.add(it.id)
            active++
            peak = maxOf(peak, active)
            delay(10)
            active--
            listOf(row(it.id))
        }
        assertEquals(machines.map { it.id }.toSet(), called.toSet())
        assertEquals(9, called.size)
        assertEquals(4, peak)
        assertEquals(9, snapshot.chats.size)
    }

    @Test fun remoteIdentityCollisionCannotOpenChatOnAnotherComputer() = runBlocking {
        val existing = mergeSharedChat(machine.copy(id = "other"), row(), null)
        val snapshot = refreshDashboard(listOf(machine), listOf(existing), DashboardSnapshot(), { false }) { listOf(row()) }
        assertEquals(setOf(machine.id), snapshot.failedMachines)
        assertTrue(snapshot.chats.isEmpty())
    }

    @Test fun deletionsAreNotResurrectedAndCancellationPropagates() = runBlocking {
        val state = refreshDashboard(listOf(machine), emptyList(), DashboardSnapshot(), { it == "removed" }) {
            listOf(row("removed"), row("deleted").put("deleted", true), row())
        }
        assertEquals(listOf("chat"), state.chats.map { it.chat.id })
        var cancelled = false
        try {
            refreshDashboard(listOf(machine), emptyList(), state, { false }) { throw CancellationException() }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}

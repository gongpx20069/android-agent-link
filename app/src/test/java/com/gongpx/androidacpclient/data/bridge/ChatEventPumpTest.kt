package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ChatEventPumpTest {
    private val scheduled = java.util.concurrent.LinkedBlockingQueue<() -> Unit>()
    private val output = mutableListOf<String>()
    private val cursors = mutableListOf<Int>()
    private val pump = ChatEventPump(
        schedule = { scheduled.add(it) },
        apply = { it() },
        checkpoint = { cursors.add(it) },
        deliver = { message, replay -> output.add("${message.text}:$replay") },
        failed = { throw AssertionError(it) },
    )

    private fun message(text: String, op: String = "one") =
        ChatMessage(MessageRole.Agent, text, 1, activityId = "stream", operationId = op)

    private fun drain() {
        while (true) (scheduled.poll() ?: break).invoke()
    }

    @Test fun joinsAdjacentDeltasAndCommitsOnlyTheirFinalCursor() {
        repeat(1000) { pump.message(it + 1, false, message("x"), 20) }
        assertEquals(1, scheduled.size)
        drain()
        assertEquals(listOf("x".repeat(1000) + ":false"), output)
        assertEquals(listOf(1000), cursors)
    }

    @Test fun neverJoinsAcrossControlOperationOrReplayBoundaries() {
        pump.message(1, true, message("before"), 20)
        pump.event(2, 20) { output.add("approval") }
        pump.message(3, true, message("after"), 20)
        pump.message(4, false, message("live"), 20)
        pump.message(5, false, message("other", "two"), 20)
        drain()
        assertEquals(listOf("before:true", "approval", "after:true", "live:false", "other:false"), output)
        assertEquals(listOf(1, 2, 3, 4, 5), cursors)
    }

    @Test fun fullQueueBackpressuresProducerAndCloseUnblocksIt() {
        repeat(256) { pump.event(it, 10) }
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val producer = Thread {
            entered.countDown()
            pump.event(257, 10)
            finished.countDown()
        }
        producer.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
        pump.close()
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        producer.join()
        drain()
        assertTrue(cursors.isEmpty())
    }

    @Test fun fullToolSnapshotsConflateWithoutDroppingControlEvents() {
        repeat(100) {
            pump.message(it + 1, false, message("status $it").copy(
                kind = ChatMessageKind.Activity, isToolSnapshot = true,
            ), 100)
        }
        pump.event(101, 10) { output.add("done") }
        drain()
        assertEquals(listOf("status 99:false", "done"), output)
        assertEquals(listOf(100, 101), cursors)
    }

    @Test fun storageBackpressureDefersApplicationWithoutAdvancingCursor() {
        var ready = false
        val local = ChatEventPump(
            { scheduled.add(it) }, { it() }, { cursors.add(it) }, { _, _ -> }, { throw AssertionError(it) }, { ready },
        )
        local.event(10, 10) { output.add("applied") }
        scheduled.remove().invoke()
        assertTrue(output.isEmpty())
        assertTrue(cursors.isEmpty())
        ready = true
        drain()
        assertEquals(listOf("applied"), output)
        assertEquals(listOf(10), cursors)
    }
}

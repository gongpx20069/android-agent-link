package com.gongpx.androidacpclient.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.gongpx.androidacpclient.data.bridge.BridgeClient
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.model.ToolDetailPage
import com.gongpx.androidacpclient.data.model.toToolActivity
import com.gongpx.androidacpclient.data.store.RecordCipher
import com.gongpx.androidacpclient.data.store.ToolDetailCache
import javax.crypto.KeyGenerator
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ToolActivityItemTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()
    private val ref = "a".repeat(48)
    private val revision = AtomicInteger(1)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cipher = RecordCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
    private val cache = ToolDetailCache(context, cipher)

    private fun item(version: Int, status: String = "in_progress") = JSONObject()
        .put("sessionUpdate", "tool_call_update").put("toolCallId", "tool")
        .put("title", "Build").put("status", status).put("detailRef", ref).put("detailVersion", version)
        .toToolActivity(1)!!

    @Before fun setup() {
        File(context.noBackupFilesDir, "tool-details").deleteRecursively()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("Bearer token", request.getHeader("Authorization"))
                val page = ToolDetailPage(ref, "b".repeat(48), revision.get(), 0, "Output v${revision.get()}", null)
                return MockResponse().setHeader("Content-Type", "application/json").setBody(page.toJson().toString())
            }
        }
        server.start()
    }
    @After fun cleanup() {
        server.shutdown()
        File(context.noBackupFilesDir, "tool-details").deleteRecursively()
    }

    @Test fun onlyExpansionLoadsRunningUpdatesDoNotPollAndCompletionRefreshesOnce() {
        val message = mutableStateOf(item(1))
        val source = ImagePromptContext(Machine("m", "M", server.url("/").toString(), "token", "f"),
            "chat", BridgeClient()) { _, _ -> false }
        compose.setContent { MaterialTheme { ToolActivityItem(message.value, source, cache) } }
        compose.waitForIdle()
        assertEquals(0, server.requestCount)
        compose.onNodeWithText("Details").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Output v1").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, server.requestCount)
        compose.runOnIdle { revision.set(2); message.value = item(2) }
        compose.waitForIdle()
        assertEquals(1, server.requestCount)
        compose.runOnIdle { revision.set(3); message.value = item(3, "completed") }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Output v3").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, server.requestCount)
        compose.onNodeWithText("Hide").performClick()
        compose.onNodeWithText("Details").performClick()
        compose.waitForIdle()
        assertEquals(2, server.requestCount)
        compose.onNodeWithText("Refresh").performClick()
        compose.waitUntil(5000) { server.requestCount == 3 }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Loading details...").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Hide").performClick()
        compose.onNodeWithText("Details").performClick()
        compose.waitForIdle()
        assertEquals(3, server.requestCount)
    }

    @Test fun completingCollapsedToolDoesNotFetchAndCacheIsEncrypted() {
        val message = mutableStateOf(item(1))
        val source = ImagePromptContext(Machine("m", "M", server.url("/").toString(), "token", "f"),
            "chat", BridgeClient()) { _, _ -> false }
        compose.setContent { MaterialTheme { ToolActivityItem(message.value, source, cache) } }
        compose.runOnIdle { revision.set(2); message.value = item(2, "completed") }
        compose.waitForIdle()
        assertEquals(0, server.requestCount)
        compose.onNodeWithText("Details").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Output v2").fetchSemanticsNodes().isNotEmpty() }
        val files = File(context.noBackupFilesDir, "tool-details").listFiles()!!
        assertTrue(files.isNotEmpty())
        assertTrue(files.none { it.readBytes().toString(Charsets.UTF_8).contains("Output v2") })
        val restored = ToolDetailCache(context, cipher).read("m:f:chat:$ref", 2)
        assertEquals("Output v2", restored!!.text)
    }

    @Test fun pagesStayFrozenAndReopeningDoesNotDownloadTheSamePage() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val offset = request.requestUrl!!.queryParameter("cursor")!!.toInt()
                val page = ToolDetailPage(ref, "b".repeat(48), revision.get(), offset,
                    "Page $offset version ${revision.get()}", if (offset == 0) 2048 else null)
                return MockResponse().setBody(page.toJson().toString())
            }
        }
        val message = mutableStateOf(item(1))
        val source = ImagePromptContext(Machine("m", "M", server.url("/").toString(), "token", "f"),
            "chat", BridgeClient()) { _, _ -> false }
        compose.setContent { MaterialTheme { ToolActivityItem(message.value, source, cache) } }
        compose.onNodeWithText("Details").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Page 0 version 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Next page").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Page 2048 version 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Hide").performClick()
        compose.onNodeWithText("Details").performClick()
        compose.waitForIdle()
        assertEquals(2, server.requestCount)
        compose.runOnIdle { revision.set(2); message.value = item(2, "completed") }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Page 0 version 2").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(3, server.requestCount)
        compose.onNodeWithText("Next page").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Page 2048 version 2").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(4, server.requestCount)
    }

    @Test fun cancellationInterruptsHttpBodyReadInsteadOfWaitingForOutput() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse()
                .setBody(ToolDetailPage(ref, "b".repeat(48), 1, 0, "Delayed", null).toJson().toString())
                .setBodyDelay(2, TimeUnit.SECONDS)
        }
        val machine = Machine("m", "M", server.url("/").toString(), "token", "f")
        val read = launch(Dispatchers.Default) { BridgeClient().toolDetails(machine, "chat", ref) }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        delay(150)
        withTimeout(1000) { read.cancelAndJoin() }
    }
}

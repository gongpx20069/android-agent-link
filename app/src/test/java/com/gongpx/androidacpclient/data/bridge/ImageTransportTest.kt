package com.gongpx.androidacpclient.data.bridge

import com.gongpx.androidacpclient.data.model.ImageAttachment
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.store.ImageStore
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ImageTransportTest {
    private val bytes = "synthetic image".toByteArray()
    private val image = ImageAttachment(ImageStore.digest(bytes), "image/png", bytes.size)
    private fun machine(server: MockWebServer) = Machine("m", "Machine", server.url("/").toString(), "test-device", "f",
        connectionHeaders = mapOf("X-Tunnel-Authorization" to "test-relay"))
    private fun caps(supported: Boolean) = MockResponse().setBody("""{"version":1,"imageSupported":$supported,"maxBytes":5242880}""")

    @Test fun evictedDownloadIsDistinguishedFromAnOldBridge() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        try {
            val client = BridgeClient()
            try {
                client.downloadImage(machine(server), "chat", image)
                fail("Missing cache must be explicit")
            } catch (_: ImageUnavailableException) { }
            try {
                client.uploadImage(machine(server), "chat", image, bytes)
                fail("An old Bridge must request an update")
            } catch (error: IOException) {
                assertFalse(error is ImageUnavailableException)
                assertTrue(error.message.orEmpty().contains("Update"))
            }
        } finally { server.shutdown() }
    }

    @Test fun authenticatedUploadAndDownloadPreserveExactBytesWithoutWebSocketBase64() = runBlocking {
        val server = MockWebServer()
        server.enqueue(caps(true))
        server.enqueue(MockResponse().setBody(image.toJson().toString()))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
        server.start()
        try {
            val client = BridgeClient()
            assertEquals(image, client.uploadImage(machine(server), "chat", image, bytes))
            assertArrayEquals(bytes, client.downloadImage(machine(server), "chat", image))
            val requests = List(3) { server.takeRequest() }
            assertEquals("/attachments/capabilities?chatId=chat", requests[0].path)
            assertEquals("POST", requests[1].method)
            assertArrayEquals(bytes, requests[1].body.readByteArray())
            requests.forEach {
                assertEquals("Bearer test-device", it.getHeader("Authorization"))
                assertEquals("test-relay", it.getHeader("X-Tunnel-Authorization"))
            }
        } finally { server.shutdown() }
    }

    @Test fun unsupportedSessionPreventsUploadAndRedirectsNeverReceiveCredentials() = runBlocking {
        val server = MockWebServer()
        val redirected = MockWebServer()
        redirected.start()
        server.enqueue(caps(false))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", redirected.url("/stolen")))
        server.start()
        try {
            try {
                BridgeClient().uploadImage(machine(server), "chat", image, bytes)
                fail("Unsupported session must not upload")
            } catch (_: IllegalStateException) { }
            try {
                BridgeClient().uploadImage(machine(server), "chat", image, bytes)
                fail("Redirect must not be followed")
            } catch (_: IOException) { }
            assertEquals(2, server.requestCount)
            assertEquals(0, redirected.requestCount)
        } finally { server.shutdown(); redirected.shutdown() }
    }

    @Test fun hashMismatchIsRejectedAndPendingRequestIsCancellable() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("wrong"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val client = BridgeClient()
            try {
                client.downloadImage(machine(server), "chat", image)
                fail("Corrupted download must be rejected")
            } catch (_: IllegalArgumentException) { }
            try {
                withTimeout(300) { client.uploadImage(machine(server), "chat", image, bytes) }
                fail("Request must cancel")
            } catch (_: TimeoutCancellationException) { }
        } finally { server.shutdown() }
    }
}

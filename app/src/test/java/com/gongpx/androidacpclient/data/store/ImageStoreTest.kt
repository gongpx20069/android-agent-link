package com.gongpx.androidacpclient.data.store

import com.gongpx.androidacpclient.data.model.ImageAttachment
import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.model.QueuedPrompt
import java.io.ByteArrayInputStream
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageStoreTest {
    @Test fun oversizedJpegIsCompressedBelowOneMiBWithoutChangingOriginal() = verifyNoisyCompression(png = false)

    @Test fun oversizedPngIsResizedBelowOneMiBAndKeepsTransparency() = verifyNoisyCompression(png = true)

    private fun verifyNoisyCompression(png: Boolean) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = ImageStore(context, RecordCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        val source = File.createTempFile("compression-source-", if (png) ".png" else ".jpg", context.cacheDir)
        val width = 1600
        val height = 1200
        val random = java.util.Random(719)
        val alpha = if (png) 0x80 else 0xff
        val pixels = IntArray(width * height) { (alpha shl 24) or random.nextInt(0x1000000) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        source.outputStream().use {
            assertTrue(bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 100, it))
        }
        bitmap.recycle()
        var target: File? = null
        try {
            assertTrue("Fixture must actually exceed the upload target", source.length() > ImageStore.UPLOAD_TARGET_BYTES)
            val originalDigest = ImageStore.digest(source.readBytes())
            val image = store.prepare(Uri.fromFile(source))
            target = File(File(context.noBackupFilesDir, "chat-images"), image.id)
            val bytes = requireNotNull(store.read(image))
            assertTrue(bytes.size in 1..ImageStore.UPLOAD_TARGET_BYTES)
            assertEquals(bytes.size, image.size)
            assertEquals(originalDigest, ImageStore.digest(source.readBytes()))
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                if (png) {
                    assertEquals("image/png", image.mimeType)
                    assertTrue(decoded.width < width)
                    assertTrue(decoded.hasAlpha())
                    assertTrue(android.graphics.Color.alpha(decoded.getPixel(0, 0)) in 1..254)
                } else {
                    assertEquals("image/jpeg", image.mimeType)
                    assertEquals(width, decoded.width)
                    assertEquals(height, decoded.height)
                }
            } finally { decoded.recycle() }
        } finally {
            assertTrue(source.delete())
            target?.let {
                assertTrue(it.delete())
                val age = File(it.parentFile, "age-" + it.name)
                if (age.exists()) assertTrue(age.delete())
            }
        }
    }

    @Test fun uploadSweepsSevenDayAgeBelowQuotaButReadsDoNotExtendOrEvict() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val cipher = RecordCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val data = (0..3).map { "aged-$it".toByteArray() }
        val images = data.map { ImageAttachment(ImageStore.digest(it), "image/png", it.size) }
        var chat = Chat("aged-chat", "Chat", "m", "M", "w", "W", "C:\\repo", "agent", "A", 1,
            activePromptImages = mapOf("running" to images[2]))
        val now = ImageStore.MAX_AGE_MILLIS + 10_000
        val store = ImageStore(context, cipher, chats = { listOf(chat) }, nowMillis = { now })
        val directory = File(context.noBackupFilesDir, "chat-images")
        fun file(index: Int) = File(directory, images[index].id)
        fun age(index: Int) = File(directory, "age-" + images[index].id)
        try {
            for (index in 0..2) store.save(images[index], data[index])
            for (index in 0..2) {
                assertTrue(age(index).setLastModified(now - ImageStore.MAX_AGE_MILLIS - 1))
            }
            assertTrue(age(1).setLastModified(now - ImageStore.MAX_AGE_MILLIS))
            assertNotNull(store.read(images[0]))
            assertTrue(file(0).exists())
            assertEquals(now - ImageStore.MAX_AGE_MILLIS - 1, age(0).lastModified())
            store.save(images[3], data[3])
            assertNull(store.read(images[0]))
            assertNotNull(store.read(images[1]))
            assertNotNull(store.read(images[2]))
            chat = chat.copy(activePromptImages = emptyMap())
            store.save(images[3], data[3])
            assertNull(store.read(images[2]))
        } finally {
            for (index in 0..3) {
                if (file(index).exists()) assertTrue(file(index).delete())
                if (age(index).exists()) assertTrue(age(index).delete())
            }
        }
    }

    @Test fun pressureEvictsOldestUnusedImageAndReadingRefreshesLru() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val cipher = RecordCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val data = (0..2).map { "cache-$it".toByteArray() }
        val images = data.map { ImageAttachment(ImageStore.digest(it), "image/png", it.size) }
        val store = ImageStore(context, cipher, 2L * (data[0].size + 28), chats = { emptyList() })
        fun file(image: ImageAttachment) = File(File(context.noBackupFilesDir, "chat-images"), image.id)
        try {
            store.save(images[0], data[0])
            store.save(images[1], data[1])
            assertTrue(file(images[0]).setLastModified(1))
            assertTrue(file(images[1]).setLastModified(2))
            store.read(images[0])
            store.save(images[2], data[2])
            assertNull(store.read(images[1]))
            assertArrayEquals(data[0], store.read(images[0]))
            assertArrayEquals(data[2], store.read(images[2]))
        } finally {
            images.forEach { if (file(it).exists()) assertTrue(file(it).delete()) }
        }
    }

    @Test fun persistedDraftQueuedAndActiveImagesAreProtectedUntilReleased() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val cipher = RecordCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val data = (0..3).map { "pinned-$it".toByteArray() }
        val images = data.map { ImageAttachment(ImageStore.digest(it), "image/png", it.size) }
        val chat = Chat("cache-chat", "Chat", "m", "M", "w", "W", "C:\\repo", "agent", "A", 1,
            queuedPrompts = listOf(QueuedPrompt("queued", "", 1, image = images[1])),
            activePromptImages = mapOf("running" to images[2]))
        val quota = 3L * (data[0].size + 28)
        val store = ImageStore(context, cipher, quota, chats = { listOf(chat) })
        fun file(image: ImageAttachment) = File(File(context.noBackupFilesDir, "chat-images"), image.id)
        try {
            store.save(images[0], data[0], chat.id)
            store.save(images[1], data[1])
            store.save(images[2], data[2])
            val reopened = ImageStore(context, cipher, quota, chats = { listOf(chat) })
            assertEquals(images[0], reopened.draft(chat.id))
            try {
                reopened.save(images[3], data[3])
                fail("No protected image may be evicted")
            } catch (_: IllegalStateException) { }
            images.take(3).forEach { assertNotNull(reopened.read(it)) }
            reopened.clearDraft(chat.id, images[0])
            reopened.save(images[3], data[3])
            assertNull(reopened.read(images[0]))
            assertNotNull(reopened.read(images[1]))
            assertNotNull(reopened.read(images[2]))
        } finally {
            store.clearDraft(chat.id, images[0])
            images.forEach { if (file(it).exists()) assertTrue(file(it).delete()) }
        }
    }

    @Test fun selectionDownsamplesAndReencodesBeforeEncryptedPersistence() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = ImageStore(context, RecordCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        val source = File.createTempFile("image-source-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(4097, 2000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.MAGENTA)
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        var target: File? = null
        try {
            val image = store.prepare(Uri.fromFile(source))
            target = File(context.noBackupFilesDir, "chat-images/${image.id}")
            val bytes = requireNotNull(store.read(image))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            assertTrue(bounds.outWidth in 1024..1025)
            assertEquals(500, bounds.outHeight)
            assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 2048)
            assertEquals("image/png", image.mimeType)
            assertTrue(image.size <= ImageAttachment.MAX_BYTES)
            assertEquals(image.id, ImageStore.digest(bytes))
        } finally {
            assertTrue(source.delete())
            target?.let { assertTrue(it.delete()) }
        }
    }

    @Test fun jpegOrientationIsAppliedAndExifIsNotCopied() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = ImageStore(context, RecordCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        val source = File.createTempFile("image-source-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        bitmap.recycle()
        android.media.ExifInterface(source.path).apply {
            setAttribute(android.media.ExifInterface.TAG_ORIENTATION, "6")
            setAttribute(android.media.ExifInterface.TAG_ARTIST, "private metadata")
            saveAttributes()
        }
        var target: File? = null
        try {
            val image = store.prepare(Uri.fromFile(source))
            target = File(context.noBackupFilesDir, "chat-images/${image.id}")
            val bytes = requireNotNull(store.read(image))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            assertEquals(400, bounds.outWidth)
            assertEquals(600, bounds.outHeight)
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("private metadata"))
        } finally {
            assertTrue(source.delete())
            target?.let { assertTrue(it.delete()) }
        }
    }

    @Test fun bytesPersistEncryptedAndAreVerifiedAfterReopening() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val cipher = RecordCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val data = "private synthetic image bytes".toByteArray()
        val image = ImageAttachment(ImageStore.digest(data), "image/png", data.size)
        val store = ImageStore(context, cipher)
        store.save(image, data)
        val file = File(context.noBackupFilesDir, "chat-images/${image.id}")
        try {
            assertFalse(file.readBytes().contentEquals(data))
            assertEquals(data.size + 28L, file.length())
            assertArrayEquals(data, ImageStore(context, cipher).read(image))
            store.save(image, data)
            assertThrows(IllegalArgumentException::class.java) { ImageStore.verify(image, "changed".toByteArray()) }
            assertThrows(IllegalArgumentException::class.java) { ImageStore.readBounded(ByteArrayInputStream(data), data.size - 1) }
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            try {
                store.read(image)
                fail("Corrupted ciphertext must not be displayed")
            } catch (_: java.security.GeneralSecurityException) { }
        } finally {
            assertTrue(file.delete())
        }
    }
}

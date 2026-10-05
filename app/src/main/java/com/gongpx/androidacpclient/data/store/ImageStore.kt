package com.gongpx.androidacpclient.data.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.security.crypto.MasterKey
import com.gongpx.androidacpclient.data.model.ImageAttachment
import com.gongpx.androidacpclient.data.model.Chat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** All reads, decoding, and encryption run off the UI thread. No public gallery copies. */
internal class ImageStore(
    private val context: Context,
    private val cipherOverride: RecordCipher? = null,
    private val quotaBytes: Long = 256L * 1024 * 1024,
    private val chats: () -> List<Chat> = { ChatStore(context).load() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val directory get() = File(context.noBackupFilesDir, "chat-images")
    private val cipher by lazy {
        if (cipherOverride != null) return@lazy cipherOverride
        val alias = "agentlink-image-records"
        MasterKey.Builder(context, alias).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        RecordCipher(keys.getKey(alias, null) as SecretKey)
    }

    suspend fun prepare(uri: Uri, draftChatId: String? = null): ImageAttachment = withContext(Dispatchers.IO) {
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open selected image" }
        val source = input.use { readBounded(it, 20 * 1024 * 1024) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        require(bounds.outMimeType in setOf("image/png", "image/jpeg")) { "Please select a PNG or JPEG image" }
        require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { "Image exceeds 16 megapixels" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) > 2048 * inSampleSize) inSampleSize *= 2
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(source, 0, source.size, options)) { "Cannot decode image" }
        try {
            val orientation = if (bounds.outMimeType == "image/jpeg") {
                ExifInterface(ByteArrayInputStream(source)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } else ExifInterface.ORIENTATION_NORMAL
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            try {
                val png = bounds.outMimeType == "image/png"
                val bytes = encodeForUpload(oriented, png)
                val image = ImageAttachment(digest(bytes), if (png) "image/png" else "image/jpeg", bytes.size)
                save(image, bytes, draftChatId)
                image
            } finally {
                if (oriented !== bitmap) oriented.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun encodeForUpload(original: Bitmap, png: Boolean): ByteArray {
        var current = original
        try {
            while (true) {
                for (quality in if (png) listOf(100) else listOf(90, 80, 70, 60)) {
                    currentCoroutineContext().ensureActive()
                    val bytes = ByteArrayOutputStream().use { output ->
                        check(current.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, output)) {
                            "Cannot encode image"
                        }
                        output.toByteArray()
                    }
                    if (bytes.size <= UPLOAD_TARGET_BYTES) return bytes
                }
                check(current.width > 1 || current.height > 1) { "Cannot compress image below 1 MiB" }
                val scaled = Bitmap.createScaledBitmap(
                    current, (current.width * 3 / 4).coerceAtLeast(1), (current.height * 3 / 4).coerceAtLeast(1), true,
                )
                if (current !== original) current.recycle()
                current = scaled
            }
        } finally {
            if (current !== original) current.recycle()
        }
    }

    suspend fun read(image: ImageAttachment): ByteArray? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val file = File(directory, image.id)
            if (!file.exists()) return@synchronized null
            require(file.length() <= ImageAttachment.MAX_BYTES + 28L) { "Invalid encrypted image size" }
            cipher.decryptBytes(image.id, file.readBytes()).also {
                verify(image, it)
                ensureAgeMarker(file)
                check(file.setLastModified(nowMillis())) { "Cannot update image cache access time" }
            }
        }
    }

    suspend fun draft(chatId: String): ImageAttachment? = withContext(Dispatchers.IO) {
        synchronized(lock) { readDraft(File(directory, draftName(chatId))) }
    }

    suspend fun clearDraft(chatId: String, image: ImageAttachment) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val file = File(directory, draftName(chatId))
            if (readDraft(file)?.id == image.id) check(file.delete()) { "Cannot remove image draft reference" }
        }
    }

    private fun readDraft(file: File): ImageAttachment? {
        if (!file.exists()) return null
        require(file.length() <= 2048) { "Invalid image draft reference" }
        return ImageAttachment.fromJson(JSONObject(cipher.decrypt(file.name, file.readBytes())))
    }

    private fun writePrivate(target: File, bytes: ByteArray) {
        val temp = File.createTempFile("upload-", ".tmp", directory)
        try {
            temp.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (temp.exists()) check(temp.delete()) { "Cannot remove temporary image" }
        }
    }

    private fun ensureAgeMarker(file: File): File {
        val age = File(directory, "age-" + file.name)
        if (!age.exists()) {
            check(age.createNewFile() && age.setLastModified(file.lastModified())) { "Cannot persist image cache age" }
        }
        return age
    }

    private fun makeRoom(incomingBytes: Long, incomingId: String) {
        val files = directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("[0-9a-f]{64}")) }
            ?: error("Cannot read image storage")
        var used = files.sumOf { it.length() }
        val cutoff = nowMillis() - MAX_AGE_MILLIS
        val expired = files.filter {
            val age = File(directory, "age-" + it.name)
            (if (age.exists()) age.lastModified() else it.lastModified()) < cutoff
        }.toSet()
        if (expired.isEmpty() && used + incomingBytes <= quotaBytes) return
        val current = chats()
        val protected = current.flatMap { chat ->
            chat.queuedPrompts.mapNotNull { it.image?.id } +
                chat.activePromptImages.values.map { it.id } +
                listOfNotNull(readDraft(File(directory, draftName(chat.id)))?.id)
        }.toSet() + incomingId
        val victims = mutableListOf<File>()
        for (file in files.sortedWith(compareBy<File> { it !in expired }.thenBy { it.lastModified() }.thenBy { it.name })) {
            if (file.name in protected) continue
            if (file !in expired && used + incomingBytes <= quotaBytes) break
            victims.add(file)
            used -= file.length()
        }
        check(used + incomingBytes <= quotaBytes) { "Image cache is full of drafts or active tasks. Finish or remove them before adding images." }
        victims.forEach {
            check(it.delete()) { "Cannot automatically clear old image cache" }
            val age = File(directory, "age-" + it.name)
            if (age.exists()) check(age.delete()) { "Cannot clear image cache age" }
        }
    }

    suspend fun save(image: ImageAttachment, bytes: ByteArray, draftChatId: String? = null) = withContext(Dispatchers.IO) {
        verify(image, bytes)
        synchronized(lock) {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create private image storage" }
            val leftovers = directory.listFiles() ?: error("Cannot read image storage")
            leftovers.filter { it.isFile && it.name.startsWith("upload-") && it.name.endsWith(".tmp") }
                .forEach { check(it.delete()) { "Cannot clear interrupted image cache write" } }
            val target = File(directory, image.id)
            val encrypted = if (target.exists()) null else cipher.encryptBytes(image.id, bytes)
            makeRoom(encrypted?.size?.toLong() ?: 0, image.id)
            if (!target.exists()) {
                writePrivate(target, requireNotNull(encrypted))
            }
            val now = nowMillis()
            check(ensureAgeMarker(target).setLastModified(now) && target.setLastModified(now)) { "Cannot update image cache timestamps" }
            if (draftChatId != null) {
                val pin = File(directory, draftName(draftChatId))
                writePrivate(pin, cipher.encrypt(pin.name, image.toJson().toString()))
            }
        }
    }

    companion object {
        private val lock = Any()
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val UPLOAD_TARGET_BYTES = 1024 * 1024
        private fun draftName(chatId: String) = "draft-" + digest(chatId.toByteArray(Charsets.UTF_8))
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun verify(image: ImageAttachment, bytes: ByteArray) {
            require(bytes.size == image.size && digest(bytes) == image.id) { "Image contents do not match attachment metadata" }
        }
        fun readBounded(input: java.io.InputStream, maximum: Int): ByteArray {
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(result.size() + count <= maximum) { "Image exceeds size limit" }
                result.write(buffer, 0, count)
            }
            return result.toByteArray()
        }
    }
}

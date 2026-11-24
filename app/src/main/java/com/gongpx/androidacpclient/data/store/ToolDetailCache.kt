package com.gongpx.androidacpclient.data.store

import android.content.Context
import android.util.AtomicFile
import androidx.security.crypto.MasterKey
import com.gongpx.androidacpclient.data.model.ToolDetailPage
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.SecretKey
import org.json.JSONObject

/** First pages only, encrypted and excluded from backup. Call on an IO dispatcher. */
internal class ToolDetailCache(context: Context, private val cipherOverride: RecordCipher? = null) {
    private val directory = File(context.noBackupFilesDir, "tool-details")
    private val cipher by lazy {
        cipherOverride ?: run {
            val alias = "agentlink-tool-details"
            MasterKey.Builder(context, alias).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            RecordCipher(keys.getKey(alias, null) as SecretKey)
        }
    }

    fun read(identity: String, version: Int): ToolDetailPage? = synchronized(lock) {
        val file = file(identity, version)
        if (!file.exists()) return null
        if (file.length() !in 28..(32768 + 28)) throw IOException("Invalid encrypted tool detail cache.")
        if (System.currentTimeMillis() - file.lastModified() > 7 * 86400_000L) {
            if (!file.delete()) throw IOException("Could not expire tool detail cache.")
            return null
        }
        val text = crypto { cipher.decrypt(file.name, AtomicFile(file).readFully()) }
        ToolDetailPage.parse(JSONObject(text))
    }

    fun write(identity: String, page: ToolDetailPage) = synchronized(lock) {
        if (page.offset != 0) return
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create tool detail cache.")
        val file = file(identity, page.version)
        val bytes = crypto { cipher.encrypt(file.name, page.toJson().toString()) }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: IOException) {
            atomic.failWrite(output)
            throw error
        }
        val files = directory.listFiles()?.filter { it.extension == "bin" }?.sortedBy { it.lastModified() }
            ?: throw IOException("Cannot list tool detail cache.")
        var size = files.sumOf { it.length() }
        for (old in files) {
            if (size <= 8 * 1024 * 1024) break
            val length = old.length()
            if (!old.delete()) throw IOException("Cannot evict tool detail cache.")
            size -= length
        }
    }

    private fun file(identity: String, version: Int): File {
        val key = MessageDigest.getInstance("SHA-256").digest("$identity:$version".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.bin")
    }

    private fun <T> crypto(action: () -> T): T = try { action() }
        catch (error: GeneralSecurityException) { throw IOException("Tool detail cache encryption failed.", error) }

    companion object { private val lock = Any() }
}

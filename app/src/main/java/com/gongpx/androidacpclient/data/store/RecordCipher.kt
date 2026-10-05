package com.gongpx.androidacpclient.data.store

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class RecordCipher(private val key: SecretKey) {
    fun encrypt(identity: String, text: String): ByteArray {
        return encryptBytes(identity, text.toByteArray(Charsets.UTF_8))
    }

    fun encryptBytes(identity: String, bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(identity.toByteArray(Charsets.UTF_8))
        return cipher.iv + cipher.doFinal(bytes)
    }

    fun decrypt(identity: String, bytes: ByteArray): String {
        return String(decryptBytes(identity, bytes), Charsets.UTF_8)
    }

    fun decryptBytes(identity: String, bytes: ByteArray): ByteArray {
        require(bytes.size >= 28) { "Invalid encrypted chat record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(identity.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }
}

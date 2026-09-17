package com.gongpx.androidacpclient.data.store

import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class RecordCipherTest {
    @Test fun roundTripUsesUniqueNoncesAndRejectsTamperingOrAnotherRow() {
        val cipher = RecordCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val text = "Private transcript \u4e2d\u6587"
        val first = cipher.encrypt("chat:one", text)
        val second = cipher.encrypt("chat:one", text)
        assertFalse(first.contentEquals(second))
        assertEquals(text, cipher.decrypt("chat:one", first))
        assertThrows(Exception::class.java) { cipher.decrypt("chat:two", first) }
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { cipher.decrypt("chat:one", first) }
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt("chat:one", byteArrayOf(1)) }
    }
}

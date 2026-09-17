package com.gongpx.androidacpclient.ui

import android.content.ClipboardManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CopyTextTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    @Test fun preservesMarkdownUnicodeAndEntirePagedSource() {
        val source = "# Heading\n```kotlin\n  val name = \"\u4e2d\u6587\"\n```\n" + "content\n".repeat(2000)
        copyPlainText(context, source)
        assertEquals(source, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun rejectsOversizeWithoutReplacingClipboardOrTruncating() {
        copyPlainText(context, "previous")
        assertThrows(IllegalArgumentException::class.java) {
            copyPlainText(context, "x".repeat(MAX_CLIPBOARD_CHARACTERS + 1))
        }
        assertEquals("previous", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun allowsExactSizeLimit() {
        val source = "x".repeat(MAX_CLIPBOARD_CHARACTERS)
        copyPlainText(context, source)
        assertEquals(source, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }
}

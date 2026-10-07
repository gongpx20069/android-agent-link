package com.gongpx.androidacpclient.ui

import androidx.compose.ui.graphics.luminance
import com.gongpx.androidacpclient.data.model.ApprovalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalCardColorsTest {
    @Test fun allStatesHaveDistinctBackgroundsAndReadableTextInBothThemes() {
        listOf(false, true).forEach { dark ->
            val palettes = ApprovalStatus.entries.map { approvalCardColors(it, dark) }
            assertEquals(ApprovalStatus.entries.size, palettes.map { it.container }.toSet().size)
            palettes.forEach { colors ->
                val background = colors.container.luminance()
                val foreground = colors.content.luminance()
                val contrast = (maxOf(background, foreground) + 0.05) / (minOf(background, foreground) + 0.05)
                assertTrue("Contrast must be at least 4.5:1 (dark=$dark, actual=$contrast)", contrast >= 4.5)
            }
        }
    }

    @Test fun previewIsBoundedAndExplicitWithoutChangingOriginalContent() {
        val content = "a".repeat(5000)
        assertEquals("short", approvalPreview("short"))
        assertEquals("a".repeat(160) + "...", approvalPreview(content))
        assertEquals(5000, content.length)
    }
}

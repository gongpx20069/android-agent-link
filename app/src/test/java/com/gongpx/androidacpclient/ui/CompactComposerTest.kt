package com.gongpx.androidacpclient.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gongpx.androidacpclient.data.model.AvailableCommand
import com.gongpx.androidacpclient.data.model.ImageAttachment
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Bitmap
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class CompactComposerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun busyComposerStaysOneRowAtNarrowWidthWithLongMultilineDraft() {
        val message = mutableStateOf("A long message ".repeat(20) + "\nsecond line\nthird line")
        val busy = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                Box(Modifier.width(300.dp)) {
                    CompactComposerRow(message.value, { message.value = it }, busy.value, true, {})
                }
            }
        } }
        val row = compose.onNodeWithTag("prompt-composer-row").fetchSemanticsNode().boundsInRoot
        assertTrue("Single-row composer must be <= 60dp even at 1.5x font", row.height <= 60f)
        val actions = compose.onNodeWithContentDescription("Chat actions").fetchSemanticsNode().boundsInRoot
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val stop = compose.onNodeWithContentDescription("Stop current task").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithContentDescription("Add").fetchSemanticsNode().boundsInRoot
        listOf(actions, stop, send).forEach {
            assertTrue("Actions retain 48dp touch targets", it.width >= 48 && it.height >= 48)
            assertEquals(row.center.y, it.center.y, 1f)
            assertTrue(it.right <= row.right && it.left >= row.left)
        }
        assertTrue(actions.right <= field.left && field.right <= stop.left && stop.right <= send.left)
        assertTrue("Text area retains at least 120dp after field padding", field.width >= 120f)
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithContentDescription("Stop current task").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
        compose.onNode(hasSetTextAction()).assertTextEquals(message.value)
    }

    @Test fun stoppingDoesNotSendOrClearDraftAndPendingStopCannotRepeat() {
        val cancelling = mutableStateOf(false)
        val online = mutableStateOf(true)
        var stopped = 0
        var sent = 0
        compose.setContent { MaterialTheme {
            ChatPromptComposer("chat", true, { sent++; true }, cancelling = cancelling.value,
                canStop = online.value, onCancelTask = { stopped++; cancelling.value = true })
        } }
        compose.onNode(hasSetTextAction()).performTextInput("Keep this")
        compose.onNodeWithContentDescription("Stop current task").performClick()
        compose.onNodeWithContentDescription("Requesting stop…").assertIsNotEnabled()
        assertEquals(1, stopped)
        assertEquals(0, sent)
        compose.onNode(hasSetTextAction()).assertTextEquals("Keep this")
        compose.onNodeWithContentDescription("Add").assertIsEnabled()
        compose.runOnIdle { cancelling.value = false; online.value = false }
        compose.onNodeWithContentDescription("Stop current task").assertIsNotEnabled()
    }

    @Test fun menuPreservesCommandGatesAndEditorPreservesNewlinesWithoutSending() {
        val draft = mutableStateOf("first\nsecond")
        var selected: String? = null
        var sent = 0
        compose.setContent { MaterialTheme {
            CompactComposerRow(draft.value, { draft.value = it }, false, true, { sent++ },
                commands = listOf(
                    PromptCommand(AvailableCommand("/config", "Settings"), false),
                    PromptCommand(AvailableCommand("/help", "Help"), true),
                ), onCommand = { selected = it.name })
        } }
        compose.onNodeWithText("/config").assertDoesNotExist()
        compose.onNodeWithContentDescription("Chat actions").performClick()
        compose.onNodeWithText("/config").assertIsNotEnabled()
        compose.onNodeWithText("/help").performClick()
        assertEquals("/help", selected)
        compose.onNodeWithContentDescription("Chat actions").performClick()
        compose.onNodeWithText("Expand editor").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("new\nmultiline\ndraft")
        compose.onNodeWithText("Done").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("new\nmultiline\ndraft")
        assertEquals(0, sent)
    }

    @Test
    @Config(sdk = [28])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun selectedImageUsesInlineThumbnailAndMenuRemovalWithoutExtraHeight() {
        val image = mutableStateOf<ImageAttachment?>(ImageAttachment("a".repeat(64), "image/png", 100))
        var removed = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.width(300.dp)) {
                    CompactComposerRow("", {}, true, image.value != null, {},
                        image = image.value, thumbnail = image.value?.let { ImageBitmap(8, 8) },
                        attachEnabled = true, onAttach = {},
                        onRemoveImage = { removed++; image.value = null })
                }
            }
        } }
        compose.onNodeWithContentDescription("Image and chat actions").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("prompt-composer-row").fetchSemanticsNode().boundsInRoot.height <= 50f)
        compose.onNodeWithContentDescription("Add").assertIsEnabled()
        compose.onNodeWithText("7-day", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Image and chat actions").performClick()
        compose.onNodeWithText("Preview image").assertIsEnabled()
        compose.onNodeWithText("Remove image").performClick()
        assertEquals(1, removed)
        compose.onNodeWithContentDescription("Chat actions").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add").assertIsNotEnabled()
    }

    @Test fun imageProcessingDisablesEditingAndSendButLeavesRemoteStopAvailable() {
        var stopped = false
        compose.setContent { MaterialTheme {
            CompactComposerRow("draft", {}, true, false, {}, working = true, editingEnabled = false,
                onCancelTask = { stopped = true }, onAttach = {}, attachEnabled = false)
        } }
        compose.onNodeWithText("draft").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Preparing image…").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Stop current task").performClick()
        assertTrue(stopped)
        compose.onNodeWithContentDescription("Chat actions").performClick()
        compose.onNodeWithText("Attach image").assertIsNotEnabled()
        compose.onNodeWithText("Expand editor").assertIsNotEnabled()
    }

    @Test
    @Config(sdk = [28])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun chineseControlsRenderAsCompactAccessibleRows() {
        var previewView: android.view.View? = null
        compose.setContent { MaterialTheme {
            previewView = LocalView.current
            CompositionLocalProvider(LocalAppStrings provides AppStrings.Chinese, LocalDensity provides Density(1f)) {
                Surface {
                    Column(Modifier.width(320.dp).padding(10.dp).testTag("composer-preview"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("空闲")
                        CompactComposerRow("", {}, false, false, {}, onAttach = {}, attachEnabled = true)
                        Text("任务执行中")
                        CompactComposerRow("帮我检查这些改动", {}, true, true, {})
                    }
                }
            }
        } }
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        compose.onNodeWithContentDescription("追加").assertIsEnabled()
        compose.onNodeWithContentDescription("停止当前任务").assertIsEnabled()
        compose.onAllNodesWithTag("prompt-composer-row").fetchSemanticsNodes().forEach {
            assertTrue("Default composer is a single 48dp row", it.boundsInRoot.height <= 48f)
        }
        compose.runOnIdle {
            val view = requireNotNull(previewView)
            val screenshot = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(screenshot))
            val output = File("build/reports/composer/compact-chinese.png")
            requireNotNull(output.parentFile).mkdirs()
            output.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            screenshot.recycle()
        }
    }
}

package com.gongpx.androidacpclient.ui

import android.content.ClipboardManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.gongpx.androidacpclient.data.model.ChatMessage
import com.gongpx.androidacpclient.data.model.ChatMessageKind
import com.gongpx.androidacpclient.data.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageSelectionTest {
    @get:Rule val compose = createComposeRule()
    private val toolbar = RecordingTextToolbar()

    private fun show(message: ChatMessage) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                    ChatTimelineItem(message)
                }
            }
        }
        compose.onNodeWithText("Copy all").assertDoesNotExist()
    }

    private fun copyFirstWord(text: String, expected: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text, useUnmergedTree = true).performTouchInput {
            longClick(Offset(8f, centerY))
        }
        compose.runOnIdle {
            assertNotNull("Long press must offer copying the selection", toolbar.copy)
            toolbar.copy!!.invoke()
            val clipboard = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
            assertEquals(expected, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun userMessageCopiesOnlyLongPressedWord() {
        show(ChatMessage(MessageRole.User, "alpha beta gamma", 0))
        copyFirstWord("alpha beta gamma", "alpha")
    }

    @Test fun markdownMessageCopiesOnlyLongPressedWord() {
        show(ChatMessage(MessageRole.Agent, "# Heading\n\nalpha beta gamma", 0))
        copyFirstWord("alpha beta gamma", "alpha")
    }

    @Test fun expandedToolDetailsRemainSelectableWithoutCopyAll() {
        show(ChatMessage(MessageRole.Agent, "Completed", 0, kind = ChatMessageKind.Activity,
            title = "Inspect files", details = "alpha beta gamma"))
        compose.onNodeWithText("Inspect files").performClick()
        copyFirstWord("alpha beta gamma", "alpha")
        compose.onNodeWithText("Copy all").assertDoesNotExist()
    }

    @Test fun planStepSupportsPartialSelection() {
        show(ChatMessage(MessageRole.Agent, "0/1", 0, kind = ChatMessageKind.Plan,
            details = """[{"content":"alpha beta gamma","status":"pending"}]"""))
        copyFirstWord("alpha beta gamma", "alpha")
    }

    private class RecordingTextToolbar : TextToolbar {
        override var status = TextToolbarStatus.Hidden
        var copy: (() -> Unit)? = null

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            copy = onCopyRequested
            status = TextToolbarStatus.Shown
        }

        override fun hide() {
            status = TextToolbarStatus.Hidden
        }
    }
}

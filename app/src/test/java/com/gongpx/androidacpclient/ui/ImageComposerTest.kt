package com.gongpx.androidacpclient.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gongpx.androidacpclient.data.bridge.BridgeClient
import com.gongpx.androidacpclient.data.model.Machine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ImageComposerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun imageEnabledComposerKeepsTextDraftWhenSubmissionIsRejected() {
        var sent = ""
        val source = ImagePromptContext(Machine("m", "M", "http://localhost", "token", "f"), "chat", BridgeClient()) { _, _ -> false }
        compose.setContent { MaterialTheme {
            ChatPromptComposer("chat", false, { sent = it; false }, imageContext = source)
        } }
        compose.onNodeWithContentDescription("Chat actions").performClick()
        compose.onNodeWithText("Attach image").assertIsEnabled()
        compose.onNodeWithText("Expand editor").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Keep this draft")
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals("Keep this draft", sent)
        compose.onNode(hasSetTextAction()).assertTextEquals("Keep this draft")
    }
}

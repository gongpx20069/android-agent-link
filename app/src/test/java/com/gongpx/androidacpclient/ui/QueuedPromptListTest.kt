package com.gongpx.androidacpclient.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.gongpx.androidacpclient.data.model.QueuedPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QueuedPromptListTest {
    @get:Rule val compose = createComposeRule()
    private val first = QueuedPrompt("first", "a".repeat(90) + "\noriginal tail", 0)
    private val second = QueuedPrompt("second", "b".repeat(90) + "\nsecond tail", 1)

    @Test fun longMessageStartsCollapsedAndClickTogglesFullUnmodifiedText() {
        compose.setContent {
            MaterialTheme { QueuedPromptList("chat", listOf(first), {}) }
        }
        compose.onNodeWithText(first.text).assertDoesNotExist()
        compose.onNodeWithText(queuedPromptPreview(first.text)).performClick()
        compose.onNodeWithText(first.text).assertExists()
        compose.onNodeWithText("Collapse").performClick()
        compose.onNodeWithText(first.text).assertDoesNotExist()
        assertEquals("a".repeat(90) + "\noriginal tail", first.text)
    }

    @Test fun removingAnExpandedMessageDoesNotExpandOrAlterTheNextOne() {
        val prompts = mutableStateOf(listOf(first, second))
        val removed = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                QueuedPromptList("chat", prompts.value) { id ->
                    removed.add(id)
                    prompts.value = prompts.value.filterNot { it.operationId == id }
                }
            }
        }
        compose.onNodeWithText(queuedPromptPreview(first.text)).performClick()
        compose.onAllNodesWithText("Remove")[0].performClick()
        compose.onNodeWithText(first.text).assertDoesNotExist()
        compose.onNodeWithText(second.text).assertDoesNotExist()
        compose.onNodeWithText(queuedPromptPreview(second.text)).assertExists()
        compose.runOnIdle {
            assertEquals(listOf("first"), removed)
            assertEquals(listOf(second), prompts.value)
        }
    }

    @Test fun largeQueueHasBoundedHeightAndCanScrollToLastMessage() {
        val prompts = (0..30).map { QueuedPrompt("op-$it", "Queued message $it", it.toLong()) }
        compose.setContent {
            MaterialTheme { QueuedPromptList("chat", prompts, {}) }
        }
        val queue = compose.onNode(hasScrollAction())
        assertTrue(queue.fetchSemanticsNode().boundsInRoot.height <= with(compose.density) { 180.dp.toPx() })
        queue.performScrollToIndex(30)
        compose.onNodeWithText("Queued message 30").assertExists()
    }
}

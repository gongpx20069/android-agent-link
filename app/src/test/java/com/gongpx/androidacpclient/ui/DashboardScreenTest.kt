package com.gongpx.androidacpclient.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gongpx.androidacpclient.data.bridge.DashboardChat
import com.gongpx.androidacpclient.data.bridge.DashboardSnapshot
import com.gongpx.androidacpclient.data.model.Chat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DashboardScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun screenKeepsAwakeOnlyWhileVisibleAndFullscreenCanBeExited() {
        val active = mutableStateOf(true)
        val fullscreen = mutableStateOf(false)
        lateinit var activity: Activity
        compose.setContent {
            activity = requireNotNull(LocalContext.current.activity())
            DashboardWindow(active.value, fullscreen.value)
            MaterialTheme {
                DashboardScreen(PaddingValues(), DashboardSnapshot(), false, fullscreen.value,
                    { fullscreen.value = it }, {}, { _, _ -> }, {})
            }
        }
        compose.runOnIdle {
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        }
        compose.onNodeWithText("Fullscreen").performClick()
        compose.onNodeWithText("Exit fullscreen").assertExists().performClick()
        compose.runOnIdle { active.value = false }
        compose.runOnIdle {
            assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test fun changedAndStaleStatesHaveExplicitLabelsAndAcknowledgement() {
        val chat = Chat("c", "A chat", "m", "Computer", "w", "Project", "C:\\repo", "agent", "Agent", 1)
        var acknowledged = false
        compose.setContent {
            MaterialTheme {
                DashboardScreen(PaddingValues(),
                    DashboardSnapshot(listOf(DashboardChat(chat, 2, "server", changed = true, stale = true))),
                    false, false, {}, {}, { machineId, chatId ->
                        acknowledged = machineId == "m" && chatId == "c"
                    }, {})
            }
        }
        compose.onNodeWithText("Offline / stale").assertExists()
        compose.onNodeWithText("Open chat").assertIsNotEnabled()
        compose.onNodeWithText("Updated · Dismiss highlight").performClick()
        assertTrue(acknowledged)
    }
}

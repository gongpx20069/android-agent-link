package com.gongpx.androidacpclient.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import android.view.WindowManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

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
        compose.onNodeWithTag("dashboard-chat-m-c").performClick()
        compose.onNodeWithText("Offline / stale").assertExists()
        compose.onNodeWithText("Open chat").assertIsNotEnabled()
        compose.onNodeWithText("Dismiss highlight").performClick()
        assertTrue(acknowledged)
    }

    @Test
    @Config(qualifiers = "w1200dp-h900dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun allChatsFitWithoutScrollingAcrossCountsOrientationAndFontScales() {
        val count = mutableStateOf(1)
        val size = mutableStateOf(320.dp to 480.dp)
        val fontScale = mutableStateOf(1f)
        val language = mutableStateOf(AppStrings.Chinese)
        lateinit var previewView: View
        val chat = Chat("c", "Example chat", "m", "Computer", "w", "Project", "C:\\repo", "agent", "Agent", 1)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppStrings provides language.value, LocalDensity provides Density(1f, fontScale.value)) {
                    previewView = LocalView.current
                    Box(Modifier.requiredSize(size.value.first, size.value.second)
                        .background(MaterialTheme.colorScheme.background).testTag("dashboard-preview")) {
                        DashboardScreen(PaddingValues(),
                            DashboardSnapshot(List(count.value) {
                                DashboardChat(chat.copy(id = "$it", title = "Project ${it + 1}",
                                    agentStatus = listOf("idle", "busy", "waitingApproval", "failed")[it % 4]),
                                    1, "s", changed = it % 5 == 0, stale = it % 11 == 10)
                            }),
                            false, false, {}, {}, { _, _ -> }, {})
                    }
                }
            }
        }
        compose.onNodeWithText("看板").assertExists()
        compose.onNodeWithText("Dashboard").assertDoesNotExist()
        for ((width, height) in listOf(320 to 480, 800 to 360, 1100 to 700)) {
            for (total in listOf(1, 8, 24, 60, 200)) {
                compose.runOnIdle { count.value = total; size.value = width.dp to height.dp; fontScale.value = 1.4f }
                val grid = compose.onNodeWithTag("dashboard-grid").fetchSemanticsNode().boundsInRoot
                val tiles = compose.onAllNodes(SemanticsMatcher("dashboard tiles") {
                    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("dashboard-chat-") == true
                }).fetchSemanticsNodes()
                assertEquals(total, tiles.size)
                tiles.forEach {
                    val bounds = it.boundsInRoot
                    assertTrue("Visible, positive-size tile at $width x $height / $total", bounds.width > 0 && bounds.height > 0)
                    assertTrue("Tile must fit inside the grid", bounds.left >= grid.left && bounds.top >= grid.top &&
                        bounds.right <= grid.right + 1 && bounds.bottom <= grid.bottom + 1)
                }
                compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
            }
        }
        compose.runOnIdle { language.value = AppStrings.English }
        compose.onNodeWithText("Dashboard").assertExists()
        compose.onNodeWithText("看板").assertDoesNotExist()
        for ((name, dimensions, total) in listOf(
            Triple("portrait", 360.dp to 640.dp, 12),
            Triple("landscape", 800.dp to 360.dp, 24),
        )) {
            compose.runOnIdle {
                language.value = AppStrings.Chinese
                size.value = dimensions
                count.value = total
                fontScale.value = 1f
            }
            val bounds = compose.onNodeWithTag("dashboard-preview").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.translate(-bounds.left, -bounds.top)
                previewView.draw(canvas)
                val folder = File(File("build", "reports"), "dashboard")
                folder.mkdirs()
                File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }
}

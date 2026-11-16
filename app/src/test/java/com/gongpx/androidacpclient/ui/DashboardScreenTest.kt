package com.gongpx.androidacpclient.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.compose.foundation.background
import android.view.WindowManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
        val landscape = mutableStateOf(false)
        lateinit var activity: Activity
        compose.setContent {
            activity = requireNotNull(LocalContext.current.activity())
            DashboardWindow(active.value, fullscreen.value, landscape.value)
            MaterialTheme {
                DashboardScreen(PaddingValues(), DashboardSnapshot(), false, fullscreen.value, landscape.value,
                    { fullscreen.value = it }, { landscape.value = it }, {}, { _, _ -> }, {})
            }
        }
        compose.runOnIdle {
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, activity.requestedOrientation)
        }
        compose.onNodeWithText("Landscape").performClick()
        compose.runOnIdle {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        }
        compose.onNodeWithText("Fullscreen").performClick()
        compose.onNodeWithText("Exit fullscreen").assertExists().performClick()
        compose.runOnIdle {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        }
        compose.onNodeWithText("Auto rotate").performClick()
        compose.runOnIdle { assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, activity.requestedOrientation) }
        compose.onNodeWithText("Landscape").performClick()
        compose.runOnIdle { active.value = false }
        compose.runOnIdle {
            assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
        }
        compose.runOnIdle { active.value = true }
        compose.runOnIdle { assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation) }
    }

    @Test fun changedAndStaleStatesHaveExplicitLabelsAndAcknowledgement() {
        val chat = Chat("c", "A chat", "m", "Computer", "w", "Project", "C:\\repo", "agent", "Agent", 1)
        var acknowledged = false
        compose.setContent {
            MaterialTheme {
                DashboardScreen(PaddingValues(),
                    DashboardSnapshot(listOf(DashboardChat(chat, 2, "server", changed = true, stale = true))),
                    false, false, false, {}, {}, {}, { machineId, chatId ->
                        acknowledged = machineId == "m" && chatId == "c"
                    }, {})
            }
        }
        compose.onNodeWithTag("dashboard-chat-m-c").performClick()
        compose.onNode(hasText("Offline / stale") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText("Open chat").assertIsNotEnabled()
        compose.onNodeWithText("Dismiss highlight").performClick()
        assertTrue(acknowledged)
    }

    @Test fun leavingCompositionRestoresExistingOrientationPolicy() {
        val visible = mutableStateOf(true)
        lateinit var activity: Activity
        compose.setContent {
            activity = requireNotNull(LocalContext.current.activity())
            remember(activity) { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT }
            if (visible.value) DashboardWindow(active = true, fullscreen = true, landscape = true)
        }
        compose.runOnIdle { assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation) }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, activity.requestedOrientation)
            assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h600dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun statusColorsStayDistinctAndLightEvenWhenUpdated() {
        val changed = mutableStateOf(false)
        val chat = Chat("c", "Example chat", "m", "Computer", "w", "Project", "C:\\repo", "agent", "Agent", 1)
        val statuses = listOf("busy", "waitingApproval", "idle", "failed", "interrupted", "unknown", "busy")
        lateinit var previewView: View
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    previewView = LocalView.current
                    Box(Modifier.requiredSize(800.dp, 600.dp).testTag("dashboard-preview")) {
                        DashboardScreen(PaddingValues(), DashboardSnapshot(statuses.mapIndexed { index, status ->
                            DashboardChat(chat.copy(id = "$index", agentStatus = status), 1, "s",
                                changed = changed.value, stale = index == statuses.lastIndex)
                        }), false, false, false, {}, {}, {}, { _, _ -> }, {})
                    }
                }
            }
        }
        fun tileColors(): List<Int> {
            val root = compose.onNodeWithTag("dashboard-preview").fetchSemanticsNode().boundsInRoot
            val tiles = statuses.indices.map {
                compose.onNodeWithTag("dashboard-chat-m-$it").fetchSemanticsNode().boundsInRoot
            }
            val bitmap = capturePreview(previewView)
            return try {
                tiles.map { bounds ->
                    bitmap.getPixel((bounds.center.x - root.left).toInt(), (bounds.bottom - root.top - 6).toInt())
                }
            } finally {
                bitmap.recycle()
            }
        }
        val colors = tileColors()
        assertEquals("Each status, including stale, has its own block color", statuses.size, colors.toSet().size)
        colors.forEach { color ->
            assertTrue("Blocks use light backgrounds", Color.red(color) >= 180 && Color.green(color) >= 180 && Color.blue(color) >= 180)
        }
        compose.runOnIdle { changed.value = true }
        assertEquals("Update highlights must not replace status colors", colors, tileColors())
        compose.onAllNodesWithText("Updated", useUnmergedTree = true).assertCountEquals(statuses.size)
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
                                    agentStatus = listOf("idle", "busy", "waitingApproval", "failed", "interrupted", "unknown")[it % 6]),
                                    1, "s", changed = it % 5 == 0, stale = it % 11 == 10)
                            }),
                            false, false, false, {}, {}, {}, { _, _ -> }, {})
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
            Triple("portrait-roomy", 360.dp to 640.dp, 6),
            Triple("landscape", 800.dp to 360.dp, 24),
            Triple("landscape-english", 800.dp to 360.dp, 24),
        )) {
            compose.runOnIdle {
                language.value = if (name.endsWith("english")) AppStrings.English else AppStrings.Chinese
                size.value = dimensions
                count.value = total
                fontScale.value = 1f
            }
            val bitmap = capturePreview(previewView)
            val folder = File(File("build", "reports"), "dashboard")
            folder.mkdirs()
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun capturePreview(view: View): Bitmap {
        val bounds = compose.onNodeWithTag("dashboard-preview").fetchSemanticsNode().boundsInRoot
        return compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            view.draw(canvas)
            bitmap
        }
    }
}

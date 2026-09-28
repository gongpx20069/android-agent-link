package com.gongpx.androidacpclient.ui

import android.Manifest
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import com.gongpx.androidacpclient.R
import com.gongpx.androidacpclient.data.notification.ChatNotificationManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun launcherUsesAdaptiveRingsAndVersionedThemedLayer() {
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
        val icon = requireNotNull(context.getDrawable(context.applicationInfo.icon))
        assertTrue(icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        val background = render(icon.background, 108)
        for ((x, y) in listOf(0 to 0, 54 to 54, 107 to 107)) {
            assertEquals(Color.rgb(25, 43, 44), background.getPixel(x, y))
        }
        val foreground = render(icon.foreground, 108)
        assertSafeCircle(foreground)
        val colors = paintedPixels(foreground).map { foreground.getPixel(it.first, it.second) }.toSet()
        for (color in listOf("#97D7C5", "#D5C1FF", "#FBF7F5")) {
            assertTrue("Missing brand color $color", Color.parseColor(color) in colors)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val monochrome = requireNotNull(icon.monochrome)
            val themed = render(monochrome, 108)
            assertSafeCircle(themed)
            assertWhiteSilhouette(themed)
        }
    }

    @Test fun notificationMarkHasTransparentEdgesAndNoOpaqueLauncherTile() {
        val notification = render(requireNotNull(context.getDrawable(R.drawable.ic_agentlink_notification)), 24)
        val pixels = paintedPixels(notification)
        assertTrue("Notification mark must be visible without filling the tile", pixels.size in 20..300)
        assertWhiteSilhouette(notification)
        for (coordinate in 0 until 24) {
            assertEquals(0, Color.alpha(notification.getPixel(coordinate, 0)))
            assertEquals(0, Color.alpha(notification.getPixel(coordinate, 23)))
            assertEquals(0, Color.alpha(notification.getPixel(0, coordinate)))
            assertEquals(0, Color.alpha(notification.getPixel(23, coordinate)))
        }
    }

    @Test fun postedAndOngoingNotificationsUseTheDedicatedSilhouette() {
        if (Build.VERSION.SDK_INT >= 33) {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notifications = ChatNotificationManager(context)
        assertEquals(R.drawable.ic_agentlink_notification, notifications.ongoingNotification(1).smallIcon.resId)
        notifications.showCompletion("icon-test", "Example chat", "Finished")
        notifications.showMonitorError("icon-test", "Example chat", "Connection unavailable")
        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        assertEquals(2, posted.size)
        posted.forEach { assertEquals(R.drawable.ic_agentlink_notification, it.smallIcon.resId) }
    }

    private fun render(drawable: Drawable, size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
        }

    private fun paintedPixels(bitmap: Bitmap): List<Pair<Int, Int>> =
        (0 until bitmap.width).flatMap { x ->
            (0 until bitmap.height).filter { y -> Color.alpha(bitmap.getPixel(x, y)) > 200 }.map { y -> x to y }
        }

    private fun assertSafeCircle(bitmap: Bitmap) {
        val pixels = paintedPixels(bitmap)
        assertTrue("Linked rings must be visible", pixels.size in 200..2000)
        for ((x, y) in pixels) {
            val dx = x + 0.5 - 54
            val dy = y + 0.5 - 54
            assertTrue("Foreground pixel $x,$y falls outside the 66-unit safe circle", dx * dx + dy * dy <= 33 * 33)
        }
    }

    private fun assertWhiteSilhouette(bitmap: Bitmap) {
        for ((x, y) in paintedPixels(bitmap)) {
            assertEquals("Silhouette must contain white alpha only", 0x00FFFFFF, bitmap.getPixel(x, y) and 0x00FFFFFF)
        }
    }
}

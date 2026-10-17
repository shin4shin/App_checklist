package com.example.homeworktracker

import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h600dp-land-mdpi")
class OverlayDrawerTest {
    private fun field(service: GameOverlayService, name: String): View? =
        GameOverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service) as View?

    private fun enter(service: GameOverlayService, pkg: String) {
        GameOverlayService::class.java.getDeclaredMethod("handleForegroundPackage", String::class.java)
            .apply { isAccessible = true }.invoke(service, pkg)
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    @Test fun movedHandleKeepsPositionAcrossAppsAndServiceRestart() {
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val service = controller.get()
        val repo = TaskRepository(service)
        repo.addApp("game.one", "Daily"); repo.addApp("game.two", "Daily")
        enter(service, "game.one")
        val handle = field(service, "tabView")!!
        touch(handle, MotionEvent.ACTION_DOWN, 10f, 10f)
        touch(handle, MotionEvent.ACTION_MOVE, 10f, 90f)
        touch(handle, MotionEvent.ACTION_MOVE, 750f, 90f)
        touch(handle, MotionEvent.ACTION_UP, 750f, 90f)
        val moved = field(service, "tabView")!!.layoutParams as WindowManager.LayoutParams
        assertEquals(service.resources.displayMetrics.widthPixels - moved.width, moved.x)
        enter(service, "game.two")
        val next = field(service, "tabView")!!.layoutParams as WindowManager.LayoutParams
        assertEquals(moved.x, next.x); assertEquals(moved.y, next.y)
        controller.destroy()
        val restarted = Robolectric.buildService(GameOverlayService::class.java).create()
        enter(restarted.get(), "game.one")
        val restored = field(restarted.get(), "tabView")!!.layoutParams as WindowManager.LayoutParams
        assertEquals(moved.x, restored.x); assertEquals(moved.y, restored.y)
        restarted.destroy()
    }

    @Test fun bothEdgesRevealConnectedHandleWithoutChangingSavedPosition() {
        for (left in listOf(true, false)) {
            val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("app_prefs", 0)
            prefs.edit().putInt("overlay_tab_x", if (left) 0 else 800).putInt("overlay_tab_y", 120).commit()
            val controller = Robolectric.buildService(GameOverlayService::class.java).create()
            val service = controller.get()
            TaskRepository(service).addApp("game.swipe", "Daily")
            enter(service, "game.swipe")
            val handle = field(service, "tabView")!!
            val handleParams = handle.layoutParams as WindowManager.LayoutParams
            assertEquals(0, handleParams.fitInsetsTypes)
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, handleParams.layoutInDisplayCutoutMode)
            touch(handle, MotionEvent.ACTION_DOWN, 400f, 140f)
            touch(handle, MotionEvent.ACTION_MOVE, if (left) 560f else 240f, 140f)
            val preview = field(service, "revealOverlayView")!!
            val panelParams = preview.layoutParams as WindowManager.LayoutParams
            assertEquals(0, panelParams.fitInsetsTypes)
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, panelParams.layoutInDisplayCutoutMode)
            val drawer = preview.findViewWithTag<android.widget.FrameLayout>("drawer")
            assertTrue(if (left) drawer.translationX < 0 else drawer.translationX > 0)
            val attached = drawer.getChildAt(1)
            assertEquals(120, (attached.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin)
            touch(handle, MotionEvent.ACTION_UP, if (left) 560f else 240f, 140f)
            assertNotNull(field(service, "overlayView"))
            assertNull(field(service, "tabView"))
            assertEquals(120, prefs.getInt("overlay_tab_y", -1))
            assertEquals(if (left) 0 else 800, prefs.getInt("overlay_tab_x", -1))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
            field(service, "overlayView")!!.findViewWithTag<View>("scrim").performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
            assertNull(field(service, "overlayView"))
            val collapsed = field(service, "tabView")!!.layoutParams as WindowManager.LayoutParams
            assertEquals(120, collapsed.y)
            assertEquals(if (left) 0 else service.resources.displayMetrics.widthPixels - collapsed.width, collapsed.x)
            controller.destroy()
        }
    }

    @Test fun cancelledDragRestoresPositionWithoutSaving() {
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val service = controller.get()
        TaskRepository(service).addApp("game.cancel", "Daily")
        enter(service, "game.cancel")
        val tab = field(service, "tabView")!!
        val initialY = (tab.layoutParams as WindowManager.LayoutParams).y
        touch(tab, MotionEvent.ACTION_DOWN, 10f, 10f)
        touch(tab, MotionEvent.ACTION_MOVE, 10f, 90f)
        touch(tab, MotionEvent.ACTION_CANCEL, 10f, 90f)
        assertEquals(initialY, (field(service, "tabView")!!.layoutParams as WindowManager.LayoutParams).y)
        controller.destroy()
    }

    @Test fun rotationClampsDisplayButDoesNotOverwriteSavedHeightOrSide() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("app_prefs", 0)
        prefs.edit().putInt("overlay_tab_x", 800).putInt("overlay_tab_y", 480).commit()
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val service = controller.get()
        TaskRepository(service).addApp("game.rotate", "Daily")
        enter(service, "game.rotate")
        RuntimeEnvironment.setQualifiers("w1200dp-h400dp-land-mdpi")
        service.onConfigurationChanged(Configuration(service.resources.configuration))
        val small = field(service, "tabView")!!.layoutParams as WindowManager.LayoutParams
        assertEquals(service.resources.displayMetrics.widthPixels - small.width, small.x)
        assertTrue(small.y + small.height <= service.resources.displayMetrics.heightPixels)
        assertEquals(480, prefs.getInt("overlay_tab_y", -1))
        RuntimeEnvironment.setQualifiers("w800dp-h600dp-land-mdpi")
        service.onConfigurationChanged(Configuration(service.resources.configuration))
        assertEquals(480, (field(service, "tabView")!!.layoutParams as WindowManager.LayoutParams).y)
        controller.destroy()
    }
}

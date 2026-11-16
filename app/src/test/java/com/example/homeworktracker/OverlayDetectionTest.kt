package com.example.homeworktracker

import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverlayDetectionTest {
    @Test fun longPressMorphsHandleAndOnlyAnActualDragSavesPosition() {
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val service = controller.get()
        val prefs = service.getSharedPreferences("app_prefs", 0)
        val before = prefs.all.toMap()
        GameOverlayService::class.java.getDeclaredMethod("minimizeToTab", String::class.java).apply { isAccessible = true }.invoke(service, "game")
        val tab = field(service, "tabView") as android.widget.FrameLayout
        val originalWidth = tab.layoutParams.width
        val originalHeight = tab.layoutParams.height
        fun touch(action: Int, x: Float = 10f, y: Float = 20f) {
            val event = android.view.MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            tab.dispatchTouchEvent(event); event.recycle()
        }
        touch(android.view.MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(850))
        assertNotNull(field(service, "deleteZoneView"))
        assertEquals(1f, tab.getChildAt(1).alpha, 0.01f)
        assertEquals(tab.layoutParams.width, tab.layoutParams.height)
        touch(android.view.MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertNull(field(service, "deleteZoneView"))
        assertSame(tab, field(service, "tabView"))
        assertEquals(0f, tab.getChildAt(1).alpha, 0.01f)
        assertEquals(originalWidth, tab.layoutParams.width)
        assertEquals(originalHeight, tab.layoutParams.height)
        assertEquals(before, prefs.all)
        touch(android.view.MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(850))
        val screenWidth = service.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds.width()
        touch(android.view.MotionEvent.ACTION_MOVE, screenWidth - 10f, 100f)
        touch(android.view.MotionEvent.ACTION_UP, screenWidth - 10f, 100f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(screenWidth, prefs.getInt("overlay_tab_x", -1))
        assertEquals(0f, tab.getChildAt(1).alpha, 0.01f)
        assertEquals(originalWidth, tab.layoutParams.width)
        assertNull(field(service, "deleteZoneView"))
        controller.destroy()
    }

    @Test fun shortDragStaysOnSameEdgeAndCannotDeleteWhileHorizontalSwipeStillOpens() {
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val service = controller.get()
        val bounds = service.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        service.getSharedPreferences("app_prefs", 0).edit().putInt("overlay_tab_x", bounds.width()).apply()
        GameOverlayService::class.java.getDeclaredField("savedX").apply { isAccessible = true }.setInt(service, bounds.width())
        GameOverlayService::class.java.getDeclaredMethod("minimizeToTab", String::class.java).apply { isAccessible = true }.invoke(service, "game")
        val tab = field(service, "tabView") as android.widget.FrameLayout
        val initial = tab.layoutParams as android.view.WindowManager.LayoutParams
        val startX = initial.x.toFloat()
        val startY = initial.y.toFloat()
        val density = service.resources.displayMetrics.density
        fun touch(view: android.view.View, action: Int, x: Float, y: Float) {
            val event = android.view.MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            view.dispatchTouchEvent(event); event.recycle()
        }
        touch(tab, android.view.MotionEvent.ACTION_DOWN, startX, startY)
        touch(tab, android.view.MotionEvent.ACTION_MOVE, startX - 20 * density, startY + 40 * density)
        assertEquals(startX.toInt(), initial.x)
        assertTrue(initial.y > startY)
        assertNull(field(service, "deleteZoneView"))
        touch(tab, android.view.MotionEvent.ACTION_MOVE, 0f, bounds.height().toFloat())
        touch(tab, android.view.MotionEvent.ACTION_UP, 0f, bounds.height().toFloat())
        assertNotNull(field(service, "tabView"))
        assertNull(field(service, "dismissedPkg"))
        assertEquals(bounds.width(), service.getSharedPreferences("app_prefs", 0).getInt("overlay_tab_x", -1))
        val restored = field(service, "tabView") as android.view.View
        touch(restored, android.view.MotionEvent.ACTION_DOWN, startX, 100f)
        touch(restored, android.view.MotionEvent.ACTION_MOVE, 0f, 100f)
        touch(restored, android.view.MotionEvent.ACTION_UP, 0f, 100f)
        assertNotNull(field(service, "overlayView"))
        assertNull(field(service, "tabView"))
        controller.destroy()
    }

    @Test fun cancellingLongPressDuringMorphRemovesAnimationAndDeleteZone() {
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val service = controller.get()
        GameOverlayService::class.java.getDeclaredMethod("minimizeToTab", String::class.java).apply { isAccessible = true }.invoke(service, "game")
        val tab = field(service, "tabView") as android.widget.FrameLayout
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_CANCEL)) {
            val event = android.view.MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(), action, 10f, 20f, 0)
            tab.dispatchTouchEvent(event); event.recycle()
            if (action == android.view.MotionEvent.ACTION_DOWN) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertNull(field(service, "tabMorphAnimator"))
        assertNull(field(service, "deleteZoneView"))
        val restored = field(service, "tabView") as android.widget.FrameLayout
        assertEquals(0f, restored.getChildAt(1).alpha, 0.01f)
        controller.destroy()
    }

    private fun field(service: GameOverlayService, name: String): Any? =
        GameOverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun focusedWindow(pkg: String): AccessibilityWindowInfo = AccessibilityWindowInfo.obtain().also {
        shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(it).setFocused(true)
        shadowOf(it).setRoot(AccessibilityNodeInfo.obtain().apply { packageName = pkg })
    }

    @Test fun foregroundMonitorFindsLateGameWithoutSystemUiEvent() {
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val service = controller.get()
        TaskRepository(service).addApp("game.late", "Daily")
        GameOverlayService::class.java.getDeclaredMethod("onServiceConnected")
            .apply { isAccessible = true }.invoke(service)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertNull(field(service, "tabView"))
        shadowOf(service).setWindows(listOf(focusedWindow("game.late")))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals("game.late", field(service, "currentPkg"))
        assertNotNull(field(service, "tabView"))
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertNull(field(service, "tabView"))
    }

    @Test fun stateEventWinsOverStaleWindowAndLeavingHidesTabImmediately() {
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val service = controller.get()
        TaskRepository(service).addApp("game.transition", "Daily")
        shadowOf(service).setWindows(listOf(focusedWindow("previous.app")))
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            .apply { packageName = "game.transition" })
        assertNotNull(field(service, "tabView"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertEquals("game.transition", field(service, "currentPkg"))
        shadowOf(service).setWindows(listOf(focusedWindow("game.transition")))
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            .apply { packageName = service.packageName })
        assertNull(field(service, "tabView"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertNull(field(service, "tabView"))
        controller.destroy()
    }

    @Test fun pendingHideDoesNotRemoveTabAfterReturningWithoutAnotherEvent() {
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val service = controller.get()
        TaskRepository(service).addApp("game.return", "Daily")
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            .apply { packageName = "game.return" })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        GameOverlayService::class.java.getDeclaredMethod("schedulePendingHide")
            .apply { isAccessible = true }.invoke(service)
        shadowOf(service).setWindows(listOf(focusedWindow("game.return")))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals("game.return", field(service, "currentPkg"))
        assertNotNull(field(service, "tabView"))
        controller.destroy()
    }

    @Test fun delayedWindowRootRestoresTabAfterLeavingApp() {
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val service = controller.get()
        TaskRepository(service).addApp("game.return", "Daily")
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertNull(field(service, "tabView"))
        shadowOf(service).setWindows(listOf(focusedWindow("game.return")))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertNotNull(field(service, "tabView"))
        controller.destroy()
    }

    @Test fun plusAddsToEachCategoryAndCancelOrBlankDoesNotSave() {
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val service = controller.get()
        val repo = TaskRepository(service)
        val pkg = "game.add"
        repo.addApp(pkg, "Daily")
        repo.saveTasks(pkg, "Daily", emptyList())
        val build = GameOverlayService::class.java.getDeclaredMethod("buildOverlayView", String::class.java)
            .apply { isAccessible = true }
        for ((index, category) in listOf("Daily", "Weekly", "Event").withIndex()) {
            val root = build.invoke(service, pkg) as android.view.View
            val container = root.findViewById<android.widget.LinearLayout>(R.id.taskContainer)
            val heading = (0 until container.childCount).map { container.getChildAt(it) }
                .filterIsInstance<android.widget.LinearLayout>()
                .first { row -> (0 until row.childCount).any { row.getChildAt(it).contentDescription == "${listOf("데일리", "위클리", "이벤트")[index]} 항목 추가" } }
            heading.getChildAt(1).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            var dialog = field(service, "addTaskDialog") as androidx.appcompat.app.AlertDialog
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(repo.tasks(pkg, category).isEmpty())
            heading.getChildAt(1).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            dialog = field(service, "addTaskDialog") as androidx.appcompat.app.AlertDialog
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing)
            assertTrue(repo.tasks(pkg, category).isEmpty())
            fun findInput(view: android.view.View): android.widget.EditText? {
                if (view is android.widget.EditText) return view
                if (view is android.view.ViewGroup) for (child in 0 until view.childCount) {
                    findInput(view.getChildAt(child))?.let { return it }
                }
                return null
            }
            findInput(dialog.window!!.decorView)!!.setText("  $category 새 항목  ")
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("$category 새 항목", repo.tasks(pkg, category).single().title)
            assertFalse(repo.tasks(pkg, category).single().done)
            assertEquals(0L, repo.tasks(pkg, category).single().deadline)
        }
        assertTrue(repo.packages("Weekly").contains(pkg))
        controller.destroy()
    }

    @Test fun firstEntryShowsOnlyTabAndRepeatedEventsKeepExpandedState() {
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val service = controller.get()
        TaskRepository(service).addApp("game.first", "Daily")
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = "game.first" }
        service.onAccessibilityEvent(event)
        assertNotNull(field(service, "tabView"))
        assertNull(field(service, "overlayView"))
        GameOverlayService::class.java.getDeclaredMethod("expandFromTab", String::class.java)
            .apply { isAccessible = true }.invoke(service, "game.first")
        val expanded = field(service, "overlayView")
        assertNotNull(expanded)
        service.onAccessibilityEvent(event)
        assertSame(expanded, field(service, "overlayView"))
        TaskRepository(service).addApp("game.second", "Event")
        event.packageName = "game.second"
        service.onAccessibilityEvent(event)
        assertNotNull(field(service, "tabView"))
        assertNull(field(service, "overlayView"))
        controller.destroy()
    }

    @Test fun windowChangesDetectGameWithoutStateEvent() {
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val service = controller.get()
        TaskRepository(service).addApp("game.window", "Daily")
        shadowOf(service).setWindows(listOf(focusedWindow("game.window")))
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals("game.window", field(service, "currentPkg"))
        assertNotNull(field(service, "tabView"))
        controller.destroy()
    }

    @Test fun contentEventUsesFocusedAppInsteadOfBackgroundSender() {
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val service = controller.get()
        TaskRepository(service).addApp("game.background", "Daily")
        shadowOf(service).setWindows(listOf(focusedWindow("other.app")))
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            .apply { packageName = "game.background" })
        assertNull(field(service, "tabView"))
        assertNull(field(service, "currentPkg"))
        controller.destroy()
    }

    @Test fun serviceConnectionFindsAlreadyOpenGame() {
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val service = controller.get()
        TaskRepository(service).addApp("game.open", "Daily")
        shadowOf(service).setWindows(listOf(focusedWindow("game.open")))
        GameOverlayService::class.java.getDeclaredMethod("onServiceConnected")
            .apply { isAccessible = true }.invoke(service)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("game.open", field(service, "currentPkg"))
        assertNotNull(field(service, "tabView"))
        controller.destroy()
    }
}

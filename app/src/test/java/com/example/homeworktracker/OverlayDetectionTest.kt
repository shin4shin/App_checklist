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
    private fun field(service: GameOverlayService, name: String): Any? =
        GameOverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun focusedWindow(pkg: String): AccessibilityWindowInfo = AccessibilityWindowInfo.obtain().also {
        shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(it).setFocused(true)
        shadowOf(it).setRoot(AccessibilityNodeInfo.obtain().apply { packageName = pkg })
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

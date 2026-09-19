package com.example.homeworktracker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChecklistRegressionTest {
    private lateinit var context: Context
    private val pkg = "com.example.homeworktracker"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        listOf(TaskRepository.STORE, "added_apps", "app_tasks", "done_status", "reset_times", "reset_drafts")
            .forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
    }

    @Test fun migrationPreservesTasksAndCompletionAndRunsOnlyOnce() {
        context.getSharedPreferences("added_apps", 0).edit().putStringSet("apps", setOf(pkg)).commit()
        context.getSharedPreferences("app_tasks", 0).edit().putString(pkg, """{"Daily":["A","B"],"Weekly":["W"],"Event":["E"]}""").commit()
        context.getSharedPreferences("done_status", 0).edit().putBoolean("${pkg}_Daily_0", true).commit()
        val repo = TaskRepository(context)
        assertEquals(listOf(true, false), repo.tasks(pkg, "Daily").map { it.done })
        assertEquals("E", repo.tasks(pkg, "Event").single().title)
        val ids = repo.tasks(pkg, "Daily").map { it.id }
        assertEquals(ids, TaskRepository(context).tasks(pkg, "Daily").map { it.id })
        repo.removeApp(pkg, "Weekly")
        assertFalse(pkg in TaskRepository(context).packages("Weekly"))
        assertTrue(pkg in repo.packages("Daily"))
    }

    @Test fun deletingCompletedFirstItemDoesNotCompleteItsSuccessor() {
        val repo = TaskRepository(context)
        repo.addApp(pkg, "Daily")
        repo.saveTasks(pkg, "Daily", listOf(TaskItem(title = "A"), TaskItem(title = "B")))
        val original = repo.tasks(pkg, "Daily")
        repo.setTaskDone(pkg, "Daily", original.first().id, true)
        repo.saveTasks(pkg, "Daily", original.drop(1))
        assertEquals(original[1].id, repo.tasks(pkg, "Daily").single().id)
        assertFalse(repo.tasks(pkg, "Daily").single().done)
    }

    @Test fun duplicateTitlesHaveIndependentIdentityAndNewItemsStartIncomplete() {
        val repo = TaskRepository(context)
        repo.addApp(pkg, "Daily")
        repo.saveTasks(pkg, "Daily", listOf(TaskItem(title = "A"), TaskItem(title = "A")))
        val original = repo.tasks(pkg, "Daily")
        repo.setTaskDone(pkg, "Daily", original[1].id, true)
        repo.saveTasks(pkg, "Daily", listOf(original[1], TaskItem(title = "A")))
        assertEquals(listOf(true, false), repo.tasks(pkg, "Daily").map { it.done })
    }

    @Test fun widgetToggleUpdatesAllTasksInOnlyItsCategory() {
        val repo = TaskRepository(context)
        repo.addApp(pkg, "Daily"); repo.addApp(pkg, "Weekly")
        HomeworkWidget().onReceive(context, Intent(HomeworkWidget.ACTION_TOGGLE_DONE)
            .putExtra(HomeworkWidget.EXTRA_PACKAGE, pkg).putExtra(HomeworkWidget.EXTRA_CATEGORY, "Weekly"))
        assertTrue(repo.tasks(pkg, "Weekly").all { it.done })
        assertFalse(repo.isDone(pkg, "Daily"))
        repo.setTaskDone(pkg, "Weekly", repo.tasks(pkg, "Weekly").first().id, false)
        assertFalse(repo.isDone(pkg, "Weekly"))
        MiniWidget().onReceive(context, Intent(MiniWidget.ACTION_TOGGLE_DONE).putExtra(MiniWidget.EXTRA_PACKAGE, pkg))
        assertTrue(repo.tasks(pkg, "Daily").all { it.done })
        SmallWidget().onReceive(context, Intent(SmallWidget.ACTION_TOGGLE_DONE).putExtra(SmallWidget.EXTRA_PACKAGE, pkg))
        assertTrue(repo.tasks(pkg, "Daily").none { it.done })
    }

    @Test fun eventMembershipIsIndependentAndHasNoAlarm() {
        val repo = TaskRepository(context)
        repo.addApp(pkg, "Event")
        assertTrue(repo.packages("Daily").isEmpty())
        context.getSharedPreferences("reset_times", 0).edit().putInt("${pkg}_Event_hour", 8).commit()
        ResetScheduler.schedule(context, pkg, "Event")
        assertNull(pending("Event"))
        repo.setDone(pkg, "Event", true)
        ResetReceiver().onReceive(context, Intent().putExtra("target_package", pkg).putExtra("target_category", "Event"))
        assertTrue(repo.isDone(pkg, "Event"))
    }

    private fun pending(category: String): PendingIntent? = PendingIntent.getBroadcast(context, 0,
        Intent(context, ResetReceiver::class.java).setData(Uri.Builder().scheme("checklist").authority("reset").appendPath(pkg).appendPath(category).build()),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    @Test fun deletingAppCancelsAlarmAndStaleReceiverDoesNotReschedule() {
        val repo = TaskRepository(context)
        repo.addApp(pkg, "Daily"); repo.addApp(pkg, "Weekly")
        val times = context.getSharedPreferences("reset_times", 0)
        times.edit().putInt("${pkg}_Daily_hour", 9).putInt("${pkg}_Weekly_hour", 10).commit()
        ResetScheduler.schedule(context, pkg, "Daily"); ResetScheduler.schedule(context, pkg, "Weekly")
        assertNotNull(pending("Daily")); assertNotNull(pending("Weekly"))
        repo.removeApp(pkg, "Weekly")
        assertNull(pending("Weekly")); assertNotNull(pending("Daily"))
        ResetReceiver().onReceive(context, Intent().putExtra("target_package", pkg).putExtra("target_category", "Weekly"))
        assertNull(pending("Weekly"))
        assertFalse(times.contains("${pkg}_Weekly_hour"))
    }

    @Test fun resetClearsOnlyTargetCategoryIncludingIndividualTasks() {
        val repo = TaskRepository(context)
        repo.addApp(pkg, "Daily"); repo.addApp(pkg, "Weekly")
        repo.setDone(pkg, "Daily", true); repo.setDone(pkg, "Weekly", true)
        ResetReceiver().onReceive(context, Intent().putExtra("target_package", pkg).putExtra("target_category", "Daily"))
        assertTrue(repo.tasks(pkg, "Daily").none { it.done })
        assertTrue(repo.isDone(pkg, "Weekly"))
    }

    @Test fun legacyFlatTasksAndDailyResetTimeAreMigrated() {
        context.getSharedPreferences("added_apps", 0).edit().putStringSet("apps", setOf(pkg)).commit()
        context.getSharedPreferences("app_tasks", 0).edit().putString(pkg, """["A","B"]""").commit()
        context.getSharedPreferences("done_status", 0).edit().putBoolean(pkg, true).commit()
        context.getSharedPreferences("reset_times", 0).edit().putInt("${pkg}_hour", 7).putInt("${pkg}_minute", 15).commit()
        val repo = TaskRepository(context)
        assertEquals(2, repo.tasks(pkg, "Daily").size)
        assertTrue(repo.tasks(pkg, "Daily").all { it.done })
        assertEquals(7, context.getSharedPreferences("reset_times", 0).getInt("${pkg}_Daily_hour", -1))
    }

    @Test fun oldRequestCodeAlarmsAreCancelledDuringMigration() {
        val repo = TaskRepository(context)
        repo.addApp(pkg, "Daily")
        val oldIntent = Intent(context, ResetReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        PendingIntent.getBroadcast(context, pkg.hashCode(), oldIntent, flags)
        PendingIntent.getBroadcast(context, "${pkg}_Daily".hashCode(), oldIntent, flags)
        ResetScheduler.cancel(context, pkg, "Daily")
        assertNull(PendingIntent.getBroadcast(context, pkg.hashCode(), oldIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
        assertNull(PendingIntent.getBroadcast(context, "${pkg}_Daily".hashCode(), oldIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
    }

    @Test fun overlayShowsCategoriesTogetherAndKeepsEmptyListControls() {
        val repo = TaskRepository(context)
        repo.addApp(pkg, "Event")
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val service = controller.get()
        val buildView = GameOverlayService::class.java.getDeclaredMethod("buildOverlayView", String::class.java).apply { isAccessible = true }
        val root = buildView.invoke(service, pkg) as android.view.View
        assertTrue(root.findViewById<android.view.View>(R.id.btnMinimizeOverlay).hasOnClickListeners())
        val container = root.findViewById<android.widget.LinearLayout>(R.id.taskContainer)
        assertEquals("이벤트", ((container.getChildAt(2) as android.widget.LinearLayout).getChildAt(0) as android.widget.TextView).text)
        assertEquals("이벤트 미션\n마감 미설정", container.getChildAt(3).findViewById<android.widget.TextView>(R.id.tvTaskName).text)
        container.getChildAt(3).performClick()
        assertTrue(repo.isDone(pkg, "Event"))
        repo.addApp(pkg, "Daily")
        val combined = buildView.invoke(service, pkg) as android.view.View
        val combinedTasks = combined.findViewById<android.widget.LinearLayout>(R.id.taskContainer)
        assertEquals(5, combinedTasks.childCount)
        repo.saveTasks(pkg, "Daily", emptyList())
        repo.saveTasks(pkg, "Event", emptyList())
        val empty = buildView.invoke(service, pkg) as android.view.View
        assertTrue(empty.findViewById<android.view.View>(R.id.btnMinimizeOverlay).hasOnClickListeners())
        controller.destroy()
    }

    @Test fun weeklyWidgetHidesUnconfiguredAppsAndShowsSavedMidnight() {
        val repo = TaskRepository(context)
        repo.addApp(pkg, "Weekly")
        repo.addApp(pkg, "Daily")
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val widgetId = shadowOf(manager).createWidget(HomeworkWidget::class.java, R.layout.widget_homework)
        fun select(category: String) {
            HomeworkWidget().onReceive(context, Intent(HomeworkWidget.ACTION_SELECT_TAB)
                .putExtra(HomeworkWidget.EXTRA_WIDGET_ID, widgetId).putExtra(HomeworkWidget.EXTRA_CATEGORY, category))
        }
        fun firstRow() = shadowOf(manager).getViewFor(widgetId).findViewById<android.view.View>(R.id.row1)
        select("Weekly")
        assertEquals(android.view.View.GONE, firstRow().visibility)
        assertTrue(pkg in repo.packages("Weekly"))
        select("Daily")
        assertEquals(android.view.View.VISIBLE, firstRow().visibility)
        context.getSharedPreferences("reset_times", 0).edit()
            .putInt("${pkg}_Weekly_hour", 0).putInt("${pkg}_Weekly_minute", 0).commit()
        select("Weekly")
        assertEquals(android.view.View.VISIBLE, firstRow().visibility)
        context.getSharedPreferences("reset_times", 0).edit().remove("${pkg}_Weekly_hour").commit()
        HomeworkWidget.updateAllWidgets(context)
        assertEquals(android.view.View.GONE, firstRow().visibility)
    }

    @Test fun widgetEventTabPersistsSelectionAndTogglesOnlyEventTasks() {
        val repo = TaskRepository(context)
        repo.addApp(pkg, "Daily"); repo.addApp(pkg, "Event")
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val widgetId = shadowOf(manager).createWidget(HomeworkWidget::class.java, R.layout.widget_homework)
        HomeworkWidget().onReceive(context, Intent(HomeworkWidget.ACTION_SELECT_TAB)
            .putExtra(HomeworkWidget.EXTRA_WIDGET_ID, widgetId).putExtra(HomeworkWidget.EXTRA_CATEGORY, "Event"))
        assertEquals("Event", context.getSharedPreferences("widget_prefs", 0).getString("tab_$widgetId", null))
        val remote = shadowOf(manager).getViewFor(widgetId)
        assertEquals("이벤트", remote.findViewById<android.widget.TextView>(R.id.tvTabEvent).text)
        val density = context.resources.displayMetrics.density
        remote.measure(android.view.View.MeasureSpec.makeMeasureSpec((250 * density).toInt(), android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec((600 * density).toInt(), android.view.View.MeasureSpec.EXACTLY))
        remote.layout(0, 0, remote.measuredWidth, remote.measuredHeight)
        for (id in listOf(R.id.tvTabDaily, R.id.tvTabWeekly, R.id.tvTabEvent)) {
            assertEquals(1, remote.findViewById<android.widget.TextView>(id).lineCount)
        }
        HomeworkWidget().onReceive(context, Intent(HomeworkWidget.ACTION_TOGGLE_DONE)
            .putExtra(HomeworkWidget.EXTRA_PACKAGE, pkg).putExtra(HomeworkWidget.EXTRA_CATEGORY, "Event"))
        assertTrue(repo.isDone(pkg, "Event"))
        assertFalse(repo.isDone(pkg, "Daily"))
    }

    @Test fun tabsHaveCorrectTitlesAndDraftSurvivesSwitchAndRecreation() {
        TaskRepository(context).addApp(pkg, "Daily")
        val controller = Robolectric.buildActivity(HomeActivity::class.java, Intent(context, HomeActivity::class.java).putExtra("category", "Daily")).setup()
        var activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        val adapter = activity.findViewById<RecyclerView>(R.id.rvApps).adapter as AppAdapter
        adapter.pendingChanges[pkg] = 11 to 35
        activity.findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_weekly
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("위클리 설정", activity.findViewById<MaterialToolbar>(R.id.toolbar).title)
        assertNotNull(activity.findViewById<MaterialToolbar>(R.id.toolbar).logo)
        activity.findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_monthly
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("이벤트 설정", activity.findViewById<MaterialToolbar>(R.id.toolbar).title)
        controller.recreate()
        activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("이벤트 설정", activity.findViewById<MaterialToolbar>(R.id.toolbar).title)
        activity.findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_daily
        shadowOf(Looper.getMainLooper()).idle()
        val restored = activity.findViewById<RecyclerView>(R.id.rvApps).adapter as AppAdapter
        assertEquals(11 to 35, restored.pendingChanges[pkg])
        restored.discardChanges()
        assertFalse(restored.hasChanges())
        controller.pause().stop().destroy()
    }
}

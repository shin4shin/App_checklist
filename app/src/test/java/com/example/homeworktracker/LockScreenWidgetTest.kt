package com.example.homeworktracker

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockScreenWidgetTest {
    @Test fun closestUnfinishedSchedulesRefreshOnChecksAndCompactSizeShowsBoth() {
        val context = RuntimeEnvironment.getApplication()
        val repo = TaskRepository(context)
        val personal = PersonalPlanRepository(context)
        val now = System.currentTimeMillis()
        repo.addApp(context.packageName, "Daily")
        repo.addApp(context.packageName, "Event")
        val event = TaskItem(title = "게임 일정", deadline = now + 7_200_000L)
        repo.saveTasks(context.packageName, "Event", listOf(event))
        val plan = TaskItem(title = "먼저 끝나는 계획", deadline = now + 3_600_000L)
        personal.save(plan)
        personal.save(TaskItem(title = "이미 지난 계획", deadline = now - 1_000L))
        val manager = AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(LockScreenWidget::class.java, R.layout.widget_lock_screen)
        LockScreenWidget.updateAllWidgets(context)
        var root = widgetTestView(context, manager, id)
        assertEquals(plan.title, root.findViewById<TextView>(R.id.lockWidgetFirstTitle).text)
        assertTrue(root.findViewById<TextView>(R.id.lockWidgetSecondTitle).text.contains(event.title))
        assertFalse(root.findViewById<TextView>(R.id.lockWidgetFirstTime).text.contains(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()))
        personal.setDone(plan.id, true)
        root = widgetTestView(context, manager, id)
        assertTrue(root.findViewById<TextView>(R.id.lockWidgetFirstTitle).text.contains(event.title))
        repo.setDone(context.packageName, "Daily", true)
        assertTrue(widgetTestView(context, manager, id).findViewById<TextView>(R.id.lockWidgetProgress).text.contains("데일리 1/1"))
        LockScreenWidget().onAppWidgetOptionsChanged(context, manager, id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 80)
        }.also { manager.updateAppWidgetOptions(id, it) })
        root = widgetTestView(context, manager, id)
        personal.setDone(plan.id, false)
        root = widgetTestView(context, manager, id)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.lockWidgetSecond).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.lockWidgetFirstTime).visibility)
        personal.setDone(plan.id, true)
        assertEquals(View.GONE, root.findViewById<View>(R.id.lockWidgetProgress).visibility)
        repo.setTaskDone(context.packageName, "Event", event.id, true)
        assertEquals("24시간 이내 일정 없음", widgetTestView(context, manager, id).findViewById<TextView>(R.id.lockWidgetFirstTitle).text)
    }
    @Test fun twentyFourHourBoundaryIsIncludedButLaterExpiredAndCompletedPlansAreExcluded() {
        val context = RuntimeEnvironment.getApplication()
        val repo = PersonalPlanRepository(context)
        val now = System.currentTimeMillis()
        val boundary = TaskItem(title = "정확히 하루 후", deadline = now + DeadlineNotifications.LEAD)
        val done = TaskItem(title = "완료", deadline = now + 3_600_000L)
        for (task in listOf(boundary, done,
            TaskItem(title = "하루 초과", deadline = now + DeadlineNotifications.LEAD + 1),
            TaskItem(title = "종료", deadline = now), TaskItem(title = "시간 없음"))) repo.save(task)
        repo.setDone(done.id, true)
        assertEquals(listOf(boundary.title), LockScreenWidget.dueEntries(context, now).map { it.title })
        repo.setDone(boundary.id, true)
        assertTrue(LockScreenWidget.dueEntries(context, now).isEmpty())
    }

    @Test fun rowIntentsOpenTheMatchingTabWithoutChangingTasks() {
        val context = RuntimeEnvironment.getApplication()
        val personal = LockScreenWidget.openIntent(context, DeadlineNotifications.Entry("계획", 1, true))
        assertTrue(personal.getBooleanExtra(DeadlineNotifications.OPEN, false))
        val weekly = LockScreenWidget.openIntent(context, DeadlineNotifications.Entry("주간", 1, false, "Weekly"))
        assertEquals("Weekly", weekly.getStringExtra("category"))
        assertFalse(weekly.getBooleanExtra(DeadlineNotifications.OPEN, true))
    }
}

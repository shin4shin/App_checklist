package com.example.homeworktracker

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import android.widget.NumberPicker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventDeadlineTest {
    @Test fun editorStartsAtRemainingWholeHoursAndUnchangedSavePreservesDeadline() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        var calls = 0
        val now = System.currentTimeMillis()
        for ((deadline, expectedDays, expectedHours) in listOf(
            Triple(now + 14 * 86_400_000L + 3 * 3_600_000L + 45 * 60_000L, 14, 3),
            Triple(now + 30 * 60_000L, 0, 0),
            Triple(now - 3_600_000L, 0, 0)
        )) {
            val dialog = EventDeadlineDialog.show(controller.get(), "이벤트", deadline) { calls++ }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(expectedDays, dialog.findViewById<NumberPicker>(R.id.eventDaysPicker)!!.value)
            assertEquals(expectedHours, dialog.findViewById<NumberPicker>(R.id.eventHoursPicker)!!.value)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(dialog.isShowing)
            assertEquals(0, calls)
        }
        controller.pause().stop().destroy()
    }

    @Test fun individualDeadlinesSurviveCompletionReorderingAndDeletion() {
        val context = RuntimeEnvironment.getApplication()
        val repo = TaskRepository(context)
        repo.addApp("events", "Event")
        val now = System.currentTimeMillis()
        val first = TaskItem(title = "첫 이벤트", deadline = EventDeadline.calculate(now, 86_400_000L))
        val second = TaskItem(title = "둘째 이벤트", deadline = EventDeadline.calculate(now, 172_800_000L))
        repo.saveTasks("events", "Event", listOf(first, second))
        repo.setTaskDone("events", "Event", first.id, true)
        repo.saveTasks("events", "Event", listOf(second, first))
        assertEquals(first.deadline, repo.tasks("events", "Event")[1].deadline)
        assertTrue(repo.tasks("events", "Event")[1].done)
        repo.saveTasks("events", "Event", listOf(second, first.copy(deadline = 0L)))
        assertEquals(second.deadline, EventDeadline.get(context, "events"))
        repo.saveTasks("events", "Event", listOf(first.copy(deadline = 0L)))
        assertEquals(0L, EventDeadline.get(context, "events"))
    }

    @Test fun legacyDeadlineMovesToExistingEventsOnly() {
        val context = RuntimeEnvironment.getApplication()
        val deadline = EventDeadline.calculate(System.currentTimeMillis(), 86_400_000L)
        context.getSharedPreferences(TaskRepository.STORE, 0).edit().putString("data",
            """{"legacy":{"Event":{"tasks":[{"id":"a","title":"A","done":true},{"id":"b","title":"B","done":false}]}}}""").commit()
        context.getSharedPreferences("reset_times", 0).edit().putLong("legacy_Event_deadline", deadline).commit()
        val repo = TaskRepository(context)
        assertTrue(repo.tasks("legacy", "Event").all { it.deadline == deadline })
        repo.saveTasks("legacy", "Event", repo.tasks("legacy", "Event") + TaskItem(title = "새 이벤트"))
        assertEquals(0L, TaskRepository(context).tasks("legacy", "Event").last().deadline)
        assertTrue(repo.tasks("legacy", "Event").first().done)
    }

    @Test fun expiredEventDoesNotHideNextDeadlineOrCloseUnsetEvents() {
        val context = RuntimeEnvironment.getApplication()
        val repo = TaskRepository(context)
        repo.addApp("mixed", "Event")
        val now = System.currentTimeMillis()
        val future = EventDeadline.calculate(now, 86_400_000L)
        repo.saveTasks("mixed", "Event", listOf(TaskItem(title = "마감", deadline = now - 1),
            TaskItem(title = "진행", deadline = future), TaskItem(title = "미설정")))
        assertEquals(future, EventDeadline.get(context, "mixed"))
        assertFalse(EventDeadline.allExpired(context, "mixed"))
        ResetReceiver().onReceive(context, Intent().putExtra("target_package", "mixed").putExtra("target_category", "Event"))
        assertEquals(future, EventDeadline.get(context, "mixed"))
    }

    @Test fun savesAnAbsoluteDeadlineAndRestoreDoesNotExtendIt() {
        val context = RuntimeEnvironment.getApplication()
        val repo = TaskRepository(context)
        repo.addApp("first", "Event"); repo.addApp("second", "Event")
        val now = System.currentTimeMillis()
        val duration = EventDeadline.durationMillis("14일 3시간")!!
        EventDeadline.save(context, listOf("first", "second"), duration, now)
        assertEquals(EventDeadline.calculate(now, duration), EventDeadline.get(context, "first"))
        assertEquals(EventDeadline.calculate(now, duration), EventDeadline.get(context, "second"))
        ResetScheduler.restore(context)
        assertEquals(EventDeadline.calculate(now, duration), EventDeadline.get(context, "first"))
        EventDeadline.save(context, listOf("first"), null)
        assertEquals(0L, EventDeadline.get(context, "first"))
        assertTrue(EventDeadline.get(context, "second") > 0)
        repo.removeApp("second", "Event")
        assertEquals(0L, EventDeadline.get(context, "second"))
    }

    @Test fun expirationKeepsTaskHistoryAndMarksWidgetAsClosed() {
        val context = RuntimeEnvironment.getApplication()
        val pkg = context.packageName
        val repo = TaskRepository(context)
        repo.addApp(pkg, "Event")
        repo.setDone(pkg, "Event", true)
        EventDeadline.save(context, listOf(pkg), 60_000L, System.currentTimeMillis() - 120_000L)
        ResetReceiver().onReceive(context, Intent().putExtra("target_package", pkg).putExtra("target_category", "Event"))
        assertTrue(repo.isDone(pkg, "Event"))
        assertTrue(EventDeadline.label(context, pkg).startsWith("마감 ·"))
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(HomeworkWidget::class.java, R.layout.widget_homework)
        HomeworkWidget().onReceive(context, Intent(HomeworkWidget.ACTION_SELECT_TAB)
            .putExtra(HomeworkWidget.EXTRA_WIDGET_ID, id).putExtra(HomeworkWidget.EXTRA_CATEGORY, "Event"))
        assertTrue(shadowOf(manager).getViewFor(id).findViewById<android.widget.TextView>(R.id.tvAppName1).text.endsWith("· 마감"))
    }

    @Test fun editorAcceptsDurationAndDoesNotSaveUntilConfirmed() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        var saved: Long? = null
        val dialog = EventDeadlineDialog.show(controller.get(), "테스트 이벤트", 0L) { saved = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        val days = dialog.findViewById<NumberPicker>(R.id.eventDaysPicker)!!
        val hours = dialog.findViewById<NumberPicker>(R.id.eventHoursPicker)!!
        days.value = 14
        hours.value = 3
        shadowOf(hours).onValueChangeListener.onValueChange(hours, 0, 3)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertNull(saved)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(EventDeadline.durationMillis("14일 3시간"), saved)
        controller.pause().stop().destroy()
    }
}

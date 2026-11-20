package com.example.homeworktracker

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventRoutineTest {
    private fun time(day: Int) = LocalDate.of(2026, 11, day).atTime(9, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun routineButtonUsesNewSelectionAndPreviousDeadlineInsteadOfOpeningRangeCalendar() {
        val controller = org.robolectric.Robolectric.buildActivity(HomeActivity::class.java).setup()
        val previous = System.currentTimeMillis() + 86_400_000L
        var saved: Pair<Long, Long>? = null
        val dialog = EventDeadlineDialog.show(controller.get(), "반복", previous,
            onRoutine = { deadline, period -> saved = deadline to period }) { fail("일반 저장 대신 루틴 저장이어야 함") }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).performClick()
        assertFalse(dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).performClick()
        dialog.findViewById<android.widget.NumberPicker>(R.id.eventDaysPicker)!!.value = 3
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).performClick()
        assertTrue(dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        val result = requireNotNull(saved)
        assertTrue(result.first > previous)
        assertEquals(EventRoutine.period(previous, result.first), result.second)
        controller.pause().stop().destroy()
    }

    @Test fun newDeadlineDefinesExactIntervalAndResetsCompletion() {
        val previous = time(8)
        val next = time(15)
        assertEquals(7L, EventRoutine.period(previous, next))
        val interval = 2 * 86_400_000L + 3 * 3_600_000L + 15 * 60_000L
        val task = TaskItem(title = "분 단위 반복", deadline = previous + interval,
            routineDays = EventRoutine.period(previous, previous + interval), done = true)
        assertEquals(task, EventRoutine.advance(task, task.deadline - 1))
        assertEquals(task.copy(deadline = task.deadline + interval, done = false), EventRoutine.advance(task, task.deadline))
        assertEquals(task.deadline + interval * 4, EventRoutine.advance(task, task.deadline + interval * 3).deadline)
        assertThrows(IllegalArgumentException::class.java) { EventRoutine.period(previous, previous) }
        assertThrows(IllegalArgumentException::class.java) { EventRoutine.period(0, next) }
    }

    @Test fun repeatsAtBoundaryAndSkipsMissedCyclesWithoutChangingTime() {
        val task = TaskItem(title = "루틴", deadline = time(8), routineDays = 7, done = true)
        assertEquals(task, EventRoutine.advance(task, time(8) - 1))
        assertEquals(task.copy(deadline = time(15), done = false), EventRoutine.advance(task, time(8)))
        assertEquals(task.copy(deadline = time(29), done = false), EventRoutine.advance(task, time(22)))
        assertEquals(task.copy(routineDays = 0), EventRoutine.advance(task.copy(routineDays = 0), time(22)))
    }

    @Test fun rangeKeepsExistingClockOrDefaultsToMidnight() {
        assertEquals(time(15), EventRoutine.deadline(LocalDate.of(2026, 11, 15), time(8)))
        val expected = LocalDate.of(2026, 11, 15).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, EventRoutine.deadline(LocalDate.of(2026, 11, 15), 0))
    }

    @Test fun receiverAdvancesPersistedRoutineAndClearsCompletion() {
        val ctx = RuntimeEnvironment.getApplication()
        val repo = TaskRepository(ctx)
        val pkg = ctx.packageName
        repo.addApp(pkg, "Event")
        val future = System.currentTimeMillis() + 86400000
        val task = TaskItem(title = "반복", deadline = future, routineDays = 7)
        repo.saveTasks(pkg, "Event", listOf(task))
        repo.setTaskDone(pkg, "Event", task.id, true)
        val prefs = ctx.getSharedPreferences(TaskRepository.STORE, 0)
        val root = org.json.JSONObject(prefs.getString("data", "{}")!!)
        root.getJSONObject(pkg).getJSONObject("Event").getJSONArray("tasks").getJSONObject(0)
            .put("deadline", System.currentTimeMillis() - 1000)
        prefs.edit().putString("data", root.toString()).commit()
        ResetReceiver().onReceive(ctx, Intent().putExtra("target_package", pkg).putExtra("target_category", "Event"))
        val next = TaskRepository(ctx).tasks(pkg, "Event").single()
        assertEquals(7L, next.routineDays)
        assertTrue(next.deadline > System.currentTimeMillis())
        assertFalse(next.done)
    }
}

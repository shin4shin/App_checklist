package com.example.homeworktracker

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
class EventResetTimeTest {
    private fun time(day: Int, hour: Int, minute: Int = 0) = LocalDate.of(2030, 11, day)
        .atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun changingDateKeepsLockedClockAndRoutine() {
        val task = TaskItem(title = "고정", deadline = time(8, 22), routineDays = 7, resetMinute = 330)
        val fixed = EventResetTime.apply(task)
        assertEquals(time(8, 5, 30), fixed.deadline)
        val next = EventResetTime.apply(fixed.copy(deadline = time(10, 12)))
        assertEquals(time(10, 5, 30), next.deadline)
        assertEquals(7L, next.routineDays)
        assertEquals(time(15, 5, 30), EventRoutine.advance(fixed, time(8, 5, 30)).deadline)
        assertEquals(0L, EventResetTime.apply(task.copy(deadline = 0)).deadline)
    }

    @Test fun repositoryAndReimportKeepClockWhileUnlockedTasksRemainUnchanged() {
        val ctx = RuntimeEnvironment.getApplication()
        val repo = TaskRepository(ctx)
        val pkg = ctx.packageName
        repo.addApp(pkg, "Event")
        val locked = TaskItem(id = "import:test", title = "고정", deadline = time(8, 18), resetMinute = 300)
        val other = TaskItem(title = "일반", deadline = time(8, 18))
        repo.saveTasks(pkg, "Event", listOf(locked, other))
        val stored = TaskRepository(ctx).tasks(pkg, "Event")
        assertEquals(time(8, 5), stored[0].deadline)
        assertEquals(300, stored[0].resetMinute)
        assertEquals(other, stored[1])
        val imported = EventImportClient.merge(stored, listOf(locked.copy(deadline = time(9, 23), resetMinute = -1)))
        assertEquals(time(9, 5), imported[0].deadline)
        repo.saveTasks(pkg, "Event", imported.map { it.copy(resetMinute = -1, deadline = time(10, 22)) })
        assertTrue(repo.tasks(pkg, "Event").all { it.deadline == time(10, 22) && it.resetMinute == -1 })
    }
}

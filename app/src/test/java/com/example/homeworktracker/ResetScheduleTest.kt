package com.example.homeworktracker

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ResetScheduleTest {
    private fun time(day: Int, hour: Int, minute: Int = 0, zone: String = "Asia/Seoul") =
        Calendar.getInstance(TimeZone.getTimeZone(zone)).apply { clear(); set(2026, Calendar.SEPTEMBER, day, hour, minute) }

    @Test fun dailyBeforeTimeUsesToday() {
        assertEquals(time(16, 9).timeInMillis, ResetSchedule.next(time(16, 8), 9, 0, emptySet()))
    }
    @Test fun exactBoundaryUsesTomorrow() {
        assertEquals(time(17, 9).timeInMillis, ResetSchedule.next(time(16, 9), 9, 0, emptySet()))
    }
    @Test fun weeklyAtBoundaryUsesNextWeek() {
        assertEquals(time(23, 9).timeInMillis, ResetSchedule.next(time(16, 9), 9, 0, setOf(Calendar.WEDNESDAY)))
    }
    @Test fun multipleWeekdaysChooseNearestFutureDate() {
        assertEquals(time(18, 9).timeInMillis, ResetSchedule.next(time(16, 10), 9, 0, setOf(Calendar.WEDNESDAY, Calendar.FRIDAY)))
    }
    @Test fun monthBoundaryIsHandled() {
        val expected = time(30, 9).apply { add(Calendar.DATE, 1) }
        assertEquals(expected.timeInMillis, ResetSchedule.next(time(30, 10), 9, 0, emptySet()))
    }
    @Test fun daylightSavingPreservesLocalResetHour() {
        val now = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply { clear(); set(2026, Calendar.MARCH, 7, 10, 0) }
        val expected = (now.clone() as Calendar).apply { add(Calendar.DATE, 1); set(Calendar.HOUR_OF_DAY, 9) }
        assertEquals(expected.timeInMillis, ResetSchedule.next(now, 9, 0, emptySet()))
    }
}

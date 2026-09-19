package com.example.homeworktracker

import org.junit.Assert.*
import org.junit.Test

class EventDurationTest {
    @Test fun deadlineAlwaysTruncatesMinutesWithoutRoundingUp() {
        for (minute in listOf(0, 29, 30, 59)) {
            val now = java.util.Calendar.getInstance().apply {
                clear()
                set(2026, java.util.Calendar.SEPTEMBER, 19, 15, minute, 59)
                set(java.util.Calendar.MILLISECOND, 999)
            }
            val expected = (now.clone() as java.util.Calendar).apply {
                add(java.util.Calendar.HOUR_OF_DAY, 3)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            assertEquals(expected.timeInMillis, EventDeadline.calculate(now.timeInMillis, 3 * 3_600_000L))
        }
    }

    @Test fun readsKoreanDaysHoursAndMinutes() {
        assertEquals(14L * 86_400_000 + 3L * 3_600_000, EventDeadline.durationMillis("14일 3시간"))
        assertEquals(9_000_000L, EventDeadline.durationMillis("2시간30분"))
        assertEquals(60_000L, EventDeadline.durationMillis("1분"))
        assertEquals(86_400_000L, EventDeadline.durationMillis("24시간"))
    }

    @Test fun rejectsIncompleteZeroNegativeDuplicateAndOverflowingInput() {
        listOf("", "14", "0일 0시간", "-1시간", "1.5시간", "1일 2일", "내일", "2시간오타",
            "99999999999999999999999일", "3651일").forEach { assertNull(it, EventDeadline.durationMillis(it)) }
    }
}

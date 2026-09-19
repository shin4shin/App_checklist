package com.example.homeworktracker

import java.util.Calendar

/** Pure scheduling rules, shared by initial registration and subsequent resets. */
object ResetSchedule {
    fun next(now: Calendar, hour: Int, minute: Int, days: Set<Int>): Long {
        require(hour in 0..23 && minute in 0..59)
        require(days.all { it in Calendar.SUNDAY..Calendar.SATURDAY })
        for (offset in 0..7) {
            val candidate = (now.clone() as Calendar).apply {
                add(Calendar.DATE, offset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidate.timeInMillis > now.timeInMillis && (days.isEmpty() || candidate.get(Calendar.DAY_OF_WEEK) in days)) {
                return candidate.timeInMillis
            }
        }
        error("No reset date found")
    }
}

package com.example.homeworktracker

import java.util.Calendar
import java.util.Locale

object EventResetTime {
    fun label(minute: Int): String = String.format(Locale.KOREA, "%02d:%02d", minute / 60, minute % 60)

    fun apply(task: TaskItem): TaskItem {
        if (task.resetMinute !in 0..1439 || task.deadline <= 0) return task
        val calendar = Calendar.getInstance().apply {
            timeInMillis = task.deadline
            set(Calendar.HOUR_OF_DAY, task.resetMinute / 60)
            set(Calendar.MINUTE, task.resetMinute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return task.copy(deadline = calendar.timeInMillis)
    }
}

package com.example.homeworktracker

import android.content.Context
import android.widget.CalendarView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object EventRoutine {
    fun advance(task: TaskItem, now: Long): TaskItem {
        if (task.routineDays <= 0 || task.deadline <= 0 || task.deadline > now) return task
        val zone = ZoneId.systemDefault()
        val original = Instant.ofEpochMilli(task.deadline).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val cycles = (ChronoUnit.DAYS.between(original.toLocalDate(), today) / task.routineDays).coerceAtLeast(0)
        var next = original.plusDays(cycles * task.routineDays)
        if (next.toInstant().toEpochMilli() <= now) next = next.plusDays(task.routineDays)
        return EventResetTime.apply(task.copy(deadline = next.toInstant().toEpochMilli(), done = false))
    }

    fun deadline(end: LocalDate, previous: Long): Long {
        val zone = ZoneId.systemDefault()
        val time = if (previous > 0) Instant.ofEpochMilli(previous).atZone(zone).toLocalTime()
            else java.time.LocalTime.MIDNIGHT
        return end.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    fun showCalendar(context: Context, current: Long, windowType: Int?, onSave: (Long, Long) -> Unit) {
        val builder = MaterialAlertDialogBuilder(context)
        val calendar = android.view.LayoutInflater.from(builder.context)
            .inflate(R.layout.dialog_routine_date, null) as CalendarView
        var start: LocalDate? = null
        var end: LocalDate? = null
        val dialog = builder.setTitle("루틴 시작일 선택").setView(calendar)
            .setNegativeButton("취소", null)
            .setPositiveButton("선택") { _, _ ->
                val first = start ?: return@setPositiveButton
                val last = end ?: return@setPositiveButton
                onSave(deadline(last, current), ChronoUnit.DAYS.between(first, last))
            }.create()
        calendar.setOnDateChangeListener { _, year, month, day ->
            val selected = LocalDate.of(year, month + 1, day)
            val first = start
            if (first == null || end != null || selected <= first) {
                start = selected
                end = null
                dialog.setTitle("${selected.monthValue}/${selected.dayOfMonth} 시작 · 종료일 선택")
            } else {
                end = selected
                dialog.setTitle("${first.monthValue}/${first.dayOfMonth} ~ ${selected.monthValue}/${selected.dayOfMonth} · ${ChronoUnit.DAYS.between(first, selected)}일")
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = end != null
        }
        windowType?.let { dialog.window?.setType(it) }
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
    }
}

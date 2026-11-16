package com.example.homeworktracker

import android.content.Context
import android.view.LayoutInflater
import android.widget.CalendarView
import android.widget.NumberPicker
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar

object EventCalendarDialog {
    fun show(context: Context, current: Long, windowType: Int?, resetMinute: Int, fixed: Boolean, onSave: (Long) -> Unit): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context)
        val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val content = LayoutInflater.from(builder.context).inflate(R.layout.dialog_event_calendar, null)
        val selected = Calendar.getInstance().apply { if (current > 0) timeInMillis = current else add(Calendar.HOUR_OF_DAY, 1) }
        val calendar = content.findViewById<CalendarView>(R.id.eventCalendar).apply { date = selected.timeInMillis }
        val hour = content.findViewById<NumberPicker>(R.id.eventCalendarHour).apply {
            minValue = 0; maxValue = 23; wrapSelectorWheel = false
            value = if (fixed) resetMinute / 60 else selected.get(Calendar.HOUR_OF_DAY)
            isEnabled = !fixed
        }
        val preview = content.findViewById<TextView>(R.id.eventCalendarPreview)
        val minute = content.findViewById<NumberPicker>(R.id.eventCalendarMinute).apply {
            minValue = 0; maxValue = 59; wrapSelectorWheel = false
            value = if (fixed) resetMinute % 60 else selected.get(Calendar.MINUTE)
            isEnabled = !fixed
        }
        fun deadline(): Long = (selected.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, if (fixed) resetMinute / 60 else hour.value)
            set(Calendar.MINUTE, if (fixed) resetMinute % 60 else minute.value)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        fun update() { preview.text = "${EventDeadline.format(deadline(), includeYear = false)} 종료" + if (fixed) " · 공통 시간 고정" else "" }
        calendar.setOnDateChangeListener { _, year, month, day -> selected.set(year, month, day); update() }
        hour.setOnValueChangedListener { _, _, _ -> update() }
        minute.setOnValueChangedListener { _, _, _ -> update() }
        val dialog = builder.setView(content).setNegativeButton("취소", null).setPositiveButton("저장", null).create()
        dialog.setOnShowListener {
            update()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { hour.clearFocus(); minute.clearFocus(); onSave(deadline()); dialog.dismiss() }
        }
        windowType?.let { dialog.window?.setType(it) }
        dialog.show()
        val metrics = context.resources.displayMetrics
        content.layoutParams.height = if (landscape) minOf((300 * metrics.density).toInt(), (metrics.heightPixels - 132 * metrics.density).toInt().coerceAtLeast((120 * metrics.density).toInt()))
            else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        dialog.window?.setLayout(minOf(((if (landscape) 600 else 360) * metrics.density).toInt(), metrics.widthPixels - (24 * metrics.density).toInt()), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }
}

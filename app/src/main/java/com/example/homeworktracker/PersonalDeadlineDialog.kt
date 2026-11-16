package com.example.homeworktracker

import android.content.Context
import android.view.LayoutInflater
import android.widget.DatePicker
import android.widget.TimePicker
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale

object PersonalDeadlineDialog {
    fun show(context: Context, target: String, currentDeadline: Long, onSave: (Long) -> Unit): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context)
        val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val content = LayoutInflater.from(builder.context).inflate(R.layout.dialog_personal_deadline, null)
        content.contentDescription = target
        val date = content.findViewById<DatePicker>(R.id.personalDeadlineDate)
        val calendar = content.findViewById<android.widget.CalendarView>(R.id.personalDeadlineCalendar)
        val time = content.findViewById<TimePicker>(R.id.personalDeadlineTime)
        val preview = content.findViewById<TextView>(R.id.personalDeadlinePreview)
        val initial = Calendar.getInstance().apply {
            if (currentDeadline > 0) timeInMillis = currentDeadline else add(Calendar.HOUR_OF_DAY, 1)
        }
        time.setIs24HourView(true)
        time.hour = initial.get(Calendar.HOUR_OF_DAY)
        time.minute = initial.get(Calendar.MINUTE)
        fun selected(): Long = Calendar.getInstance().apply {
            clear()
            isLenient = false
            set(date?.year ?: initial.get(Calendar.YEAR), date?.month ?: initial.get(Calendar.MONTH),
                date?.dayOfMonth ?: initial.get(Calendar.DAY_OF_MONTH), time.hour, time.minute, 0)
        }.timeInMillis
        fun update() {
            preview.text = try {
                SimpleDateFormat("yyyy년 M월 d일 HH시 mm분까지", Locale.KOREAN).format(java.util.Date(selected()))
            } catch (_: IllegalArgumentException) { "유효한 날짜와 시간을 선택해 주세요" }
        }
        date?.init(initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH)) { _, _, _, _ -> update() }
        calendar?.apply {
            this.date = initial.timeInMillis
            setOnDateChangeListener { _, year, month, day -> initial.set(year, month, day); update() }
        }
        time.setOnTimeChangedListener { _, _, _ -> update() }
        val dialog = builder.setView(content)
            .setNegativeButton("취소", null).setPositiveButton("저장", null)
            .apply { if (currentDeadline > 0) setNeutralButton("시간 설정 해제") { _, _ -> onSave(0L) } }.create()
        dialog.setOnShowListener {
            update()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                date?.clearFocus(); calendar?.clearFocus(); time.clearFocus()
                if (!time.validateInput()) return@setOnClickListener
                val deadline = try { selected() } catch (_: IllegalArgumentException) { update(); return@setOnClickListener }
                onSave(deadline)
                dialog.dismiss()
            }
        }
        dialog.show()
        val metrics = context.resources.displayMetrics
        if (landscape) content.layoutParams.height = minOf((300 * metrics.density).toInt(),
            (metrics.heightPixels - 132 * metrics.density).toInt().coerceAtLeast((120 * metrics.density).toInt()))
        dialog.window?.setLayout(minOf(((if (landscape) 640 else 360) * metrics.density).toInt(),
            metrics.widthPixels - (24 * metrics.density).toInt()), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }
}

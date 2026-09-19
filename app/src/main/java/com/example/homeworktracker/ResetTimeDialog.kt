package com.example.homeworktracker

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.TimePicker
import androidx.appcompat.app.AlertDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar
import java.util.Locale

/** Shared editor for one app and a multi-selection. Save is explicit and immediate. */
object ResetTimeDialog {
    fun show(
        context: Context,
        category: String,
        targetLabel: String,
        hour: Int,
        minute: Int,
        days: Set<Int>,
        onSave: (Int, Int, Set<Int>) -> Unit
    ): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context)
        val content = LayoutInflater.from(builder.context).inflate(R.layout.dialog_time_picker, null)
        val picker = content.findViewById<TimePicker>(R.id.resetTimePicker)
        picker.setIs24HourView(true)
        picker.hour = hour.coerceIn(0, 23)
        picker.minute = minute.coerceIn(0, 59)
        content.findViewById<TextView>(R.id.resetTargetLabel).text = targetLabel
        val weekly = category == "Weekly"
        val dayGroup = content.findViewById<ChipGroup>(R.id.resetDayGroup)
        content.findViewById<View>(R.id.layoutDayPicker).visibility = if (weekly) View.VISIBLE else View.GONE
        val dayLabels = listOf(Calendar.MONDAY to "월", Calendar.TUESDAY to "화", Calendar.WEDNESDAY to "수",
            Calendar.THURSDAY to "목", Calendar.FRIDAY to "금", Calendar.SATURDAY to "토", Calendar.SUNDAY to "일")
        val chips = dayLabels.map { (day, label) ->
            Chip(builder.context).apply {
                id = View.generateViewId()
                text = label
                contentDescription = "${label}요일"
                isCheckable = true
                isCheckedIconVisible = true
                ensureAccessibleTouchTarget((48 * context.resources.displayMetrics.density).toInt())
                isChecked = day in days
                dayGroup.addView(this)
            } to day
        }
        fun selectedDays() = if (weekly) chips.filter { it.first.isChecked }.map { it.second }.toSet() else emptySet()
        val summary = content.findViewById<TextView>(R.id.resetSummary)
        val error = content.findViewById<TextView>(R.id.resetDayError)
        val dialog = builder.setTitle(if (weekly) R.string.reset_weekly_title else R.string.reset_daily_title)
            .setView(content).setNegativeButton(R.string.reset_cancel, null)
            .setPositiveButton(R.string.reset_save, null).create()
        fun updateSummary() {
            val selected = selectedDays()
            val valid = !weekly || selected.isNotEmpty()
            error.visibility = if (valid) View.GONE else View.VISIBLE
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = valid
            val time = String.format(Locale.KOREAN, "%02d:%02d", picker.hour, picker.minute)
            summary.text = when {
                !valid -> context.getString(R.string.reset_select_days)
                weekly -> context.getString(R.string.reset_weekly_summary,
                    dayLabels.filter { it.first in selected }.joinToString("·") { it.second }, time)
                else -> context.getString(R.string.reset_daily_summary, time)
            }
        }
        picker.setOnTimeChangedListener { _, _, _ -> updateSummary() }
        dayGroup.setOnCheckedStateChangeListener { _, _ -> updateSummary() }
        dialog.setOnShowListener {
            updateSummary()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                picker.clearFocus()
                if (!picker.validateInput() || (weekly && selectedDays().isEmpty())) return@setOnClickListener
                onSave(picker.hour, picker.minute, selectedDays())
                dialog.dismiss()
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
        return dialog
    }
}

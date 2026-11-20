package com.example.homeworktracker

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.NumberPicker
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object EventDeadlineDialog {
    /** windowType: 액티비티가 아닌 곳(오버레이 서비스 등)에서 띄울 때 지정한다. */
    fun show(
        context: Context,
        target: String,
        currentDeadline: Long,
        windowType: Int? = null,
        dialogTitle: String = "이벤트 시간 설정",
        onRoutine: ((Long, Long) -> Unit)? = null,
        resetMinute: Int = 240,
        fixed: Boolean = false,
        onDateSave: ((Long) -> Unit)? = null,
        onSave: (Long?) -> Unit
    ): AlertDialog {
        val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val compact = landscape || windowType == android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val builder = MaterialAlertDialogBuilder(context)
        val content = LayoutInflater.from(builder.context).inflate(
            if (compact) R.layout.dialog_event_deadline_compact else R.layout.dialog_event_deadline, null)
        content.contentDescription = "$dialogTitle · $target"
        val days = content.findViewById<NumberPicker>(R.id.eventDaysPicker).apply {
            minValue = 0
            maxValue = 3650
            wrapSelectorWheel = false
        }
        val hours = content.findViewById<NumberPicker>(R.id.eventHoursPicker).apply {
            minValue = 0
            maxValue = 23
            wrapSelectorWheel = false
        }
        val remainingHours = ((currentDeadline - System.currentTimeMillis()).coerceAtLeast(0L) / 3_600_000L)
            .coerceAtMost(3650L * 24 + 23)
        val initialDays = (remainingHours / 24).toInt()
        val initialHours = (remainingHours % 24).toInt()
        days.value = initialDays
        hours.value = initialHours
        fun unchanged() = currentDeadline > 0 && days.value == initialDays && hours.value == initialHours
        fun duration() = days.value * 86_400_000L + hours.value * 3_600_000L
        var routine = false
        fun selectedDeadline() = if (unchanged()) currentDeadline else EventDeadline.calculate(System.currentTimeMillis(), duration(), resetMinute, fixed)
        val preview = content.findViewById<TextView>(R.id.eventDeadlinePreview)
        val dialog = builder.setView(content)
            .setNegativeButton("취소", null).setPositiveButton("저장", null)
            .apply { if (onRoutine != null) setNeutralButton("루틴", null) }.create()
        if (onDateSave != null) {
            val container = content.findViewById<android.widget.LinearLayout>(R.id.eventDeadlineActions)
                ?: ((content as android.widget.ScrollView).getChildAt(0) as android.widget.LinearLayout)
            container.addView(com.google.android.material.button.MaterialButton(builder.context).apply {
                text = "달력으로 설정"
                textSize = 12f
                minimumWidth = 0; minimumHeight = 0
                val density = resources.displayMetrics.density
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, (40 * density).toInt()).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                }
                setOnClickListener {
                    EventCalendarDialog.show(context, currentDeadline, windowType, resetMinute, fixed) { deadline ->
                        if (routine) {
                            if (deadline <= currentDeadline) {
                                android.widget.Toast.makeText(context, "이전 종료 시간보다 늦게 설정해 주세요.", android.widget.Toast.LENGTH_SHORT).show()
                                return@show
                            }
                            onRoutine?.invoke(deadline, EventRoutine.period(currentDeadline, deadline))
                        } else onDateSave(deadline)
                        dialog.dismiss()
                    }
                }
            }, 1)
        }
        fun validate() {
            val duration = duration()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = (unchanged() || duration > 0) && (!routine || selectedDeadline() > currentDeadline)
            if (compact) {
                preview.visibility = android.view.View.GONE
                return
            }
            preview.visibility = if (unchanged()) android.view.View.GONE else android.view.View.VISIBLE
            preview.text = if (duration == 0L) "1시간 이상 선택해 주세요."
                else "예상 종료 시간: ${EventDeadline.format(EventDeadline.calculate(System.currentTimeMillis(), duration, resetMinute, fixed))}"
        }
        days.setOnValueChangedListener { _, _, _ -> validate() }
        hours.setOnValueChangedListener { _, _, _ -> validate() }
        dialog.setOnShowListener {
            validate()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                if (currentDeadline <= 0) {
                    android.widget.Toast.makeText(context, "종료 시간을 먼저 저장해 주세요.", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                routine = !routine
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text = if (routine) "루틴 켜짐" else "루틴"
                validate()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                days.clearFocus()
                hours.clearFocus()
                if (routine) {
                    val next = selectedDeadline()
                    if (next <= currentDeadline) return@setOnClickListener
                    onRoutine?.invoke(next, EventRoutine.period(currentDeadline, next))
                } else {
                    if (unchanged()) { dialog.dismiss(); return@setOnClickListener }
                    if (duration() == 0L) return@setOnClickListener
                    onSave(duration())
                }
                dialog.dismiss()
            }
        }
        windowType?.let { dialog.window?.setType(it) }
        dialog.show()
        if (compact) {
            val metrics = context.resources.displayMetrics
            val width = minOf((320 * metrics.density).toInt(), metrics.widthPixels - (24 * metrics.density).toInt())
            content.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.AT_MOST),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
            val available = (metrics.heightPixels - 180 * metrics.density).toInt().coerceAtLeast((80 * metrics.density).toInt())
            if (!landscape && content.measuredHeight > available) content.layoutParams.height = available
            dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }
}

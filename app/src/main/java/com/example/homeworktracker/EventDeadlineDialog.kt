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
        onSave: (Long?) -> Unit
    ): AlertDialog {
        val compact = windowType == android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val builder = MaterialAlertDialogBuilder(context)
        val content = LayoutInflater.from(builder.context).inflate(
            if (compact) R.layout.dialog_event_deadline_compact else R.layout.dialog_event_deadline, null)
        content.findViewById<TextView>(R.id.eventDeadlineTarget).apply {
            text = target
            if (compact) { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        }
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
        val preview = content.findViewById<TextView>(R.id.eventDeadlinePreview)
        val original = if (currentDeadline > 0) "현재 마감: ${EventDeadline.format(currentDeadline)}\n" else ""
        preview.text = original + "일·시간을 돌려 남은 기간을 선택하세요."
        val dialog = builder.setTitle(dialogTitle).setView(content)
            .setNegativeButton("취소", null).setPositiveButton("저장", null)
            .apply { if (currentDeadline > 0) setNeutralButton("마감 해제") { _, _ -> onSave(null) } }.create()
        fun validate() {
            val duration = duration()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = unchanged() || duration > 0
            if (compact) {
                preview.text = when {
                    unchanged() -> "${EventDeadline.format(currentDeadline, includeYear = false)} 마감\n남은 시간 ${days.value}일 ${hours.value}시간"
                    duration == 0L -> "1시간 이상 선택해 주세요."
                    else -> "${EventDeadline.format(EventDeadline.calculate(System.currentTimeMillis(), duration), includeYear = false)} 마감\n분·초는 버리고 저장합니다."
                }
                return
            }
            preview.text = if (unchanged()) original + "남은 시간: ${days.value}일 ${hours.value}시간\n변경 없이 저장하면 기존 마감을 유지합니다."
                else if (duration == 0L) original + "1시간 이상 선택해 주세요."
                else "${days.value}일 ${hours.value}시간 후\n예상 마감: ${EventDeadline.format(EventDeadline.calculate(System.currentTimeMillis(), duration))}\n분·초는 버리고 정각으로 저장합니다."
        }
        days.setOnValueChangedListener { _, _, _ -> validate() }
        hours.setOnValueChangedListener { _, _, _ -> validate() }
        dialog.setOnShowListener {
            validate()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                days.clearFocus()
                hours.clearFocus()
                if (unchanged()) { dialog.dismiss(); return@setOnClickListener }
                if (duration() == 0L) return@setOnClickListener
                onSave(duration())
                dialog.dismiss()
            }
        }
        windowType?.let { dialog.window?.setType(it) }
        dialog.show()
        if (compact) {
            val metrics = context.resources.displayMetrics
            val width = minOf((320 * metrics.density).toInt(), metrics.widthPixels - (24 * metrics.density).toInt())
            // Keep buttons visible in landscape; only the picker content scrolls.
            content.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.AT_MOST),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
            val available = (metrics.heightPixels - 180 * metrics.density).toInt().coerceAtLeast((80 * metrics.density).toInt())
            if (content.measuredHeight > available) content.layoutParams.height = available
            dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }
}

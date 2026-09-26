package com.example.homeworktracker

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PersonalPlansFragment : Fragment() {
    private val handler = Handler(Looper.getMainLooper())
    private var shown: List<TaskItem>? = null
    private val deadlines = mutableMapOf<String, TextView>()
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 60_000L) }
    }
    private var editor: androidx.appcompat.app.AlertDialog? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_personal_plans, container, false)
    override fun onViewCreated(view: View, state: Bundle?) {
        view.findViewById<View>(R.id.homeModeSwitch).setOnClickListener { (requireActivity() as HomeActivity).switchPlannerMode() }
        view.findViewById<View>(R.id.addPersonalPlan).setOnClickListener { editPlan(null) }
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroyView() {
        handler.removeCallbacks(refresh)
        editor?.dismiss(); editor = null
        deadlines.clear(); shown = null
        super.onDestroyView()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun render() {
        val root = view ?: return
        val plans = PersonalPlanRepository(requireContext()).plans().sortedWith(
            compareBy<TaskItem> { it.done }.thenBy { if (it.deadline > 0) it.deadline else Long.MAX_VALUE }.thenBy { it.title })
        root.findViewById<TextView>(R.id.personalSummary).text = "전체 ${plans.size}개 · 완료 ${plans.count { it.done }}개"
        if (shown == plans) {
            plans.forEach { deadlines[it.id]?.text = EventDeadline.label(it.deadline) }
            return
        }
        shown = plans
        deadlines.clear()
        val list = root.findViewById<LinearLayout>(R.id.personalPlanList).apply { removeAllViews() }
        if (plans.isEmpty()) list.addView(TextView(requireContext()).apply {
            text = "아직 계획이 없어요.\n제목과 마감 시간을 직접 정해 보세요."
            setPadding(dp(8), dp(32), dp(8), dp(32))
            setTextColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        for (plan in plans) {
            val card = MaterialCardView(requireContext()).apply {
                radius = dp(16).toFloat(); strokeWidth = 0; cardElevation = 0f
                setCardBackgroundColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurfaceVariant))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            }
            val line = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(12), dp(8), dp(12))
            }
            line.addView(MaterialCheckBox(requireContext()).apply {
                isChecked = plan.done
                contentDescription = "${plan.title} 완료"
                setOnCheckedChangeListener { _, done ->
                    PersonalPlanRepository(requireContext()).setDone(plan.id, done); render()
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            val labels = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(requireContext()).apply {
                text = plan.title; textSize = 17f
                setTextColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface))
                if (plan.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            })
            val time = TextView(requireContext()).apply {
                text = EventDeadline.label(plan.deadline); textSize = 12f
                setPadding(0, dp(6), 0, 0)
                setTextColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            deadlines[plan.id] = time
            labels.addView(time)
            line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(MaterialButton(requireContext()).apply {
                text = "수정"; contentDescription = "${plan.title} 수정"
                setOnClickListener { editPlan(plan) }
            })
            card.addView(line); list.addView(card)
        }
    }
    private fun editPlan(original: TaskItem?) {
        val item = original ?: TaskItem(title = "")
        var deadline = item.deadline
        val builder = MaterialAlertDialogBuilder(requireContext())
        val content = layoutInflater.inflate(R.layout.dialog_personal_plan, null)
        val title = content.findViewById<EditText>(R.id.personalPlanTitle).apply { setText(item.title) }
        val time = content.findViewById<TextView>(R.id.personalPlanDeadline)
        fun updateTime() { time.text = EventDeadline.label(deadline) }
        updateTime()
        content.findViewById<View>(R.id.personalPlanSetTime).setOnClickListener {
            PersonalDeadlineDialog.show(requireContext(), title.text.toString().ifBlank { "새 계획" }, deadline) { selected ->
                deadline = selected
                updateTime()
            }
        }
        val dialog = builder.setTitle(if (original == null) "계획 추가" else "계획 수정")
            .setView(content).setNegativeButton("취소", null).setPositiveButton("저장", null)
            .apply { if (original != null) setNeutralButton("삭제") { _, _ ->
                MaterialAlertDialogBuilder(requireContext()).setTitle("계획 삭제")
                    .setMessage("‘${item.title}’ 계획을 삭제할까요?")
                    .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                        PersonalPlanRepository(requireContext()).delete(item.id); render()
                    }.show()
            } }.create()
        editor = dialog
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = title.text.toString().trim()
                if (name.isEmpty()) { title.error = "제목을 입력해 주세요"; return@setOnClickListener }
                PersonalPlanRepository(requireContext()).save(item.copy(title = name, deadline = deadline))
                dialog.dismiss(); render()
            }
        }
        dialog.show()
    }
}

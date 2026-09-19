package com.example.homeworktracker

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {
    private var expiredExpanded = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { updateSummary(); handler.postDelayed(this, 30_000L) }
    }
    private val stateListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateSummary() }
    override fun onResume() {
        super.onResume()
        requireContext().getSharedPreferences(TaskRepository.STORE, 0).registerOnSharedPreferenceChangeListener(stateListener)
        handler.post(refresh)
    }
    override fun onPause() {
        handler.removeCallbacks(refresh)
        requireContext().getSharedPreferences(TaskRepository.STORE, 0).unregisterOnSharedPreferenceChangeListener(stateListener)
        super.onPause()
    }
    private fun navigate(id: Int) {
        requireActivity().findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = id
    }
    private fun appName(pkg: String): String = try {
        val pm = requireContext().packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { pkg }

    private fun updateSummary() {
        val root = view ?: return
        val repo = TaskRepository(requireContext())
        val now = System.currentTimeMillis()
        val daily = repo.packages("Daily").associateWith { repo.tasks(it, "Daily") }
        val tasks = daily.values.flatten()
        val done = tasks.count { it.done }
        val percent = if (tasks.isEmpty()) 0 else done * 100 / tasks.size
        root.findViewById<TextView>(R.id.tvTodayDate).text = SimpleDateFormat("M월 d일 EEEE", Locale.KOREAN).format(Date(now))
        root.findViewById<TextView>(R.id.tvProgressPercent).text = "$percent%"
        root.findViewById<TextView>(R.id.tvProgressSummary).text = "오늘 할 일 ${tasks.size}개 중 ${done}개 완료"
        root.findViewById<LinearProgressIndicator>(R.id.homeProgress).progress = percent
        root.findViewById<TextView>(R.id.tvHomeEncouragement).text = when {
            tasks.isEmpty() -> "첫 할 일을 추가하고 시작해 보세요"
            done == tasks.size -> "오늘 할 일을 모두 마쳤어요!"
            else -> "${tasks.size - done}개만 더 체크하면 오늘도 완료!"
        }
        val events = repo.packages("Event").flatMap { pkg -> repo.tasks(pkg, "Event").map { pkg to it } }
        val upcoming = events.filter { !it.second.done && it.second.deadline > now }.sortedBy { it.second.deadline }.take(3)
        val eventList = root.findViewById<LinearLayout>(R.id.homeEvents).apply { removeAllViews() }
        if (upcoming.isEmpty()) message(eventList, "다가오는 이벤트 마감이 없어요")
        for ((pkg, task) in upcoming) {
            val hours = (task.deadline - now) / 3_600_000L
            val remaining = when {
                hours == 0L -> "1시간 미만 남음"
                hours < 24L -> "${hours}시간 남음"
                else -> "${hours / 24}일 ${hours % 24}시간 남음"
            }
            row(eventList, pkg, task.title, "${appName(pkg)} · $remaining", R.id.nav_monthly)
        }
        val expired = events.filter { it.second.deadline in 1..now }.sortedByDescending { it.second.deadline }
        val toggle = root.findViewById<TextView>(R.id.homeExpiredToggle)
        toggle.visibility = if (expired.isEmpty()) View.GONE else View.VISIBLE
        toggle.text = "마감된 이벤트 ${expired.size}개 ${if (expiredExpanded) "접기 ▴" else "보기 ▾"}"
        val expiredList = root.findViewById<LinearLayout>(R.id.homeExpiredEvents).apply {
            removeAllViews(); visibility = if (expiredExpanded) View.VISIBLE else View.GONE
        }
        if (expiredExpanded) for ((pkg, task) in expired) {
            row(expiredList, pkg, task.title, "${appName(pkg)} · ${EventDeadline.label(task.deadline)}", R.id.nav_monthly)
        }
        val pending = root.findViewById<LinearLayout>(R.id.homePendingApps).apply { removeAllViews() }
        val unfinished = daily.filterValues { items -> items.any { !it.done } }.toList().sortedBy { appName(it.first) }
        if (unfinished.isEmpty()) message(pending, if (tasks.isEmpty()) "등록된 데일리 할 일이 없어요" else "모든 앱의 데일리 체크 완료 ✓")
        for ((pkg, items) in unfinished) row(pending, pkg, appName(pkg), "${items.count { it.done }} / ${items.size} 완료", R.id.nav_daily)
        root.findViewById<TextView>(R.id.btnGoDaily).text = if (tasks.isEmpty()) "데일리 할 일 추가하기" else "데일리 목록 보기"
    }
    private fun message(parent: LinearLayout, text: String) {
        parent.addView(TextView(requireContext()).apply {
            this.text = text
            setTextColor(MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(16), 0, dp(16))
        })
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun row(parent: LinearLayout, pkg: String, title: String, detail: String, destination: Int) {
        val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
            radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = 0
            setCardBackgroundColor(MaterialColors.getColor(parent, com.google.android.material.R.attr.colorSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            isClickable = true; isFocusable = true
            setOnClickListener { navigate(destination) }
            contentDescription = "$title, $detail, 목록 열기"
        }
        val line = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        line.addView(ImageView(requireContext()).apply {
            try { setImageDrawable(requireContext().packageManager.getApplicationIcon(pkg)) }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { setImageResource(R.mipmap.ic_launcher) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val labels = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(requireContext()).apply {
            text = title; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurface))
        })
        labels.addView(TextView(requireContext()).apply {
            text = detail; textSize = 12f; setPadding(0, dp(6), 0, 0)
            setTextColor(MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(line); parent.addView(card)
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btnGoDaily).setOnClickListener { navigate(R.id.nav_daily) }
        view.findViewById<View>(R.id.homeExpiredToggle).setOnClickListener { expiredExpanded = !expiredExpanded; updateSummary() }
    }
}

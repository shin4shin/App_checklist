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
    private var selectedCategory = "Daily"
    private val categoryLabels = listOf("데일리", "위클리", "이벤트")
    private data class HomeRow(val key: String, val pkg: String, val title: String, val detail: String, val destination: Int)
    private data class RowViews(val card: View, val detail: TextView)
    private data class RenderedList(val identity: List<HomeRow>, val rows: List<RowViews>)
    private val renderedLists = mutableMapOf<Int, RenderedList>()
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { updateSummary(); handler.postDelayed(this, 60_000L) }
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
        val allTasks = TaskRepository.categories.associateWith { category ->
            repo.packages(category).flatMap { repo.tasks(it, category) }
        }
        val tabs = root.findViewById<com.google.android.material.tabs.TabLayout>(R.id.homeProgressTabs)
        TaskRepository.categories.forEachIndexed { index, category ->
            val items = allTasks.getValue(category)
            tabs.getTabAt(index)?.text = "${categoryLabels[index]} ${items.count { it.done }}/${items.size}"
        }
        val categoryLabel = categoryLabels[TaskRepository.categories.indexOf(selectedCategory)]
        val accent = when (selectedCategory) {
            "Weekly" -> android.graphics.Color.parseColor("#3FB950")
            "Event" -> android.graphics.Color.parseColor("#F0883E")
            else -> MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnPrimaryContainer)
        }
        val surface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface)
        val background = if (selectedCategory == "Daily")
            MaterialColors.getColor(root, com.google.android.material.R.attr.colorPrimaryContainer)
        else androidx.core.graphics.ColorUtils.blendARGB(surface, accent, 0.18f)
        root.findViewById<com.google.android.material.card.MaterialCardView>(R.id.homeProgressCard)
            .setCardBackgroundColor(background)
        root.findViewById<TextView>(R.id.homeProgressTitle).setTextColor(accent)
        root.findViewById<TextView>(R.id.tvHomeEncouragement).setTextColor(accent)
        root.findViewById<LinearProgressIndicator>(R.id.homeProgress).apply {
            setIndicatorColor(accent)
            trackColor = androidx.core.graphics.ColorUtils.blendARGB(background, accent, 0.18f)
        }
        tabs.setSelectedTabIndicatorColor(accent)
        tabs.setTabTextColors(MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant), accent)
        val tasks = allTasks.getValue(selectedCategory)
        val done = tasks.count { it.done }
        val percent = if (tasks.isEmpty()) 0 else done * 100 / tasks.size
        root.findViewById<TextView>(R.id.tvTodayDate).text = SimpleDateFormat("M월 d일 EEEE", Locale.KOREAN).format(Date(now))
        root.findViewById<TextView>(R.id.tvProgressPercent).text = "$percent%"
        root.findViewById<TextView>(R.id.tvProgressSummary).text = "$categoryLabel ${tasks.size}개 중 ${done}개 완료"
        root.findViewById<TextView>(R.id.homeProgressTitle).text = "$categoryLabel 진행 상황"
        root.findViewById<LinearProgressIndicator>(R.id.homeProgress).progress = percent
        root.findViewById<TextView>(R.id.tvHomeEncouragement).text = when {
            tasks.isEmpty() -> "$categoryLabel 항목을 추가하고 시작해 보세요"
            done == tasks.size -> "$categoryLabel 항목을 모두 마쳤어요!"
            else -> "$categoryLabel ${tasks.size - done}개 남았어요"
        }
        val events = repo.packages("Event").flatMap { pkg -> repo.tasks(pkg, "Event").map { pkg to it } }
        val upcoming = events.filter { !it.second.done && it.second.deadline > now }.sortedBy { it.second.deadline }.take(3)
        renderList(R.id.homeEvents, upcoming.map { (pkg, task) ->
            HomeRow("$pkg:${task.id}", pkg, task.title, "${appName(pkg)} · ${EventDeadline.remaining(task.deadline, now)}", R.id.nav_monthly)
        }, "다가오는 이벤트 마감이 없어요")
        val expired = events.filter { it.second.deadline in 1..now }.sortedByDescending { it.second.deadline }
        val toggle = root.findViewById<TextView>(R.id.homeExpiredToggle)
        toggle.visibility = if (expired.isEmpty()) View.GONE else View.VISIBLE
        toggle.text = "마감된 이벤트 ${expired.size}개 ${if (expiredExpanded) "접기 ▴" else "보기 ▾"}"
        root.findViewById<LinearLayout>(R.id.homeExpiredEvents).visibility = if (expiredExpanded) View.VISIBLE else View.GONE
        if (expiredExpanded) renderList(R.id.homeExpiredEvents, expired.map { (pkg, task) ->
            HomeRow("$pkg:${task.id}", pkg, task.title, "${appName(pkg)} · ${EventDeadline.label(task.deadline, now)}", R.id.nav_monthly)
        }, "마감된 이벤트가 없어요")
        val unfinished = daily.filterValues { items -> items.any { !it.done } }.toList().sortedBy { appName(it.first) }
        val dailyEmpty = daily.values.all { it.isEmpty() }
        renderList(R.id.homePendingApps, unfinished.map { (pkg, items) ->
            HomeRow(pkg, pkg, appName(pkg), "${items.count { it.done }} / ${items.size} 완료", R.id.nav_daily)
        }, if (dailyEmpty) "등록된 데일리 할 일이 없어요" else "모든 앱의 데일리 체크 완료 ✓")
        root.findViewById<TextView>(R.id.btnGoDaily).text = if (dailyEmpty) "데일리 할 일 추가하기" else "데일리 목록 보기"
    }

    private fun renderList(id: Int, rows: List<HomeRow>, emptyMessage: String) {
        val parent = view?.findViewById<LinearLayout>(id) ?: return
        // Time and completion text can change without replacing cards or their icons.
        val identity = rows.map { it.copy(detail = "") }
        val previous = renderedLists[id]
        if (previous != null && previous.identity == identity && rows.isNotEmpty()) {
            rows.zip(previous.rows).forEach { (item, views) ->
                if (views.detail.text.toString() != item.detail) views.detail.text = item.detail
                views.card.contentDescription = "${item.title}, ${item.detail}, 목록 열기"
            }
            return
        }
        if (rows.isEmpty() && previous?.identity == identity && parent.childCount == 1) {
            (parent.getChildAt(0) as TextView).text = emptyMessage
            return
        }
        parent.removeAllViews()
        if (rows.isEmpty()) message(parent, emptyMessage)
        val views = rows.map { row(parent, it.pkg, it.title, it.detail, it.destination) }
        renderedLists[id] = RenderedList(identity, views)
    }

    private fun message(parent: LinearLayout, text: String) {
        parent.addView(TextView(requireContext()).apply {
            this.text = text
            setTextColor(MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(16), 0, dp(16))
        })
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun row(parent: LinearLayout, pkg: String, title: String, detail: String, destination: Int): RowViews {
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
        val detailView = TextView(requireContext()).apply {
            text = detail; textSize = 12f; setPadding(0, dp(6), 0, 0)
            setTextColor(MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        labels.addView(detailView)
        line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(line); parent.addView(card)
        return RowViews(card, detailView)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("progressCategory", selectedCategory)
        outState.putBoolean("expiredExpanded", expiredExpanded)
    }
    override fun onDestroyView() {
        handler.removeCallbacks(refresh)
        renderedLists.clear()
        super.onDestroyView()
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectedCategory = savedInstanceState?.getString("progressCategory") ?: selectedCategory
        expiredExpanded = savedInstanceState?.getBoolean("expiredExpanded") ?: expiredExpanded
        val tabs = view.findViewById<com.google.android.material.tabs.TabLayout>(R.id.homeProgressTabs)
        categoryLabels.forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.getTabAt(TaskRepository.categories.indexOf(selectedCategory))?.select()
        tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                selectedCategory = TaskRepository.categories[tab.position]
                updateSummary()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
        view.findViewById<View>(R.id.btnGoDaily).setOnClickListener { navigate(R.id.nav_daily) }
        view.findViewById<View>(R.id.homeExpiredToggle).setOnClickListener { expiredExpanded = !expiredExpanded; updateSummary() }
    }
}

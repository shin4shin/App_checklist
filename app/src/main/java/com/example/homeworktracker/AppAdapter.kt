// AppAdapter.kt
package com.example.homeworktracker

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val context: Context,
    private val appList: MutableList<AppInfo>,
    private val onRemove: (AppInfo) -> Unit,
    private val onSave: () -> Unit,
    private val onSelectionChanged: (Boolean) -> Unit,
    private val onTaskClick: (AppInfo) -> Unit = {},
    private val category: String = "Daily"
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val SORT_NAME = 0
        const val SORT_TIME = 1
    }

    val pendingChanges = mutableMapOf<String, Pair<Int, Int>>()
    val pendingDays = mutableMapOf<String, Set<Int>>()
    private val draftPrefs = context.getSharedPreferences("reset_drafts", Context.MODE_PRIVATE)

    init {
        val draft = org.json.JSONObject(draftPrefs.getString(category, "{}")!!)
        val valid = TaskRepository(context).packages(category)
        for (pkg in draft.keys()) {
            if (pkg !in valid || category == "Event") continue
            val value = draft.getJSONObject(pkg)
            pendingChanges[pkg] = value.getInt("hour") to value.getInt("minute")
            if (value.has("days")) pendingDays[pkg] = value.getString("days").split(",").mapNotNull { it.toIntOrNull() }.toSet()
        }
    }

    fun persistDraft() {
        val draft = org.json.JSONObject()
        for ((pkg, time) in pendingChanges) {
            val value = org.json.JSONObject().put("hour", time.first).put("minute", time.second)
            pendingDays[pkg]?.let { value.put("days", it.joinToString(",")) }
            draft.put(pkg, value)
        }
        draftPrefs.edit().putString(category, draft.toString()).apply()
    }

    // 선택 모드
    var isSelectionMode = false
        private set
    val selectedPackages = mutableSetOf<String>()

    // 정렬
    var sortMode = context.getSharedPreferences("sort_prefs", Context.MODE_PRIVATE)
        .getInt("sort_mode", SORT_NAME)
        private set

    inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvCheck: TextView = view.findViewById(R.id.tvCheck)
        val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvName: TextView = view.findViewById(R.id.tvAppName)
        val tvResetTime: TextView = view.findViewById(R.id.tvResetTime)
        val btnSet: Button = view.findViewById(R.id.btnSetTime)
    }

    override fun getItemCount() = appList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return AppViewHolder(LayoutInflater.from(context).inflate(R.layout.item_app, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        holder as AppViewHolder
        val app = appList[position]
        holder.ivIcon.setImageDrawable(app.icon)
        holder.tvName.text = app.name

        if (isSelectionMode) {
            // 선택 모드: 왼쪽에 빈 원 or 채워진 원
            val isSelected = selectedPackages.contains(app.packageName)
            holder.tvCheck.visibility = View.VISIBLE
            holder.tvCheck.text = if (isSelected) "●" else "○"
            holder.tvCheck.setTextColor(
                if (isSelected) android.graphics.Color.parseColor("#4CAF50")
                else android.graphics.Color.parseColor("#888888")
            )
            holder.tvName.setTextColor(android.graphics.Color.WHITE)
            holder.btnSet.visibility = View.GONE

            holder.itemView.setOnClickListener {
                if (isSelected) selectedPackages.remove(app.packageName)
                else selectedPackages.add(app.packageName)
                notifyItemChanged(position)
            }
            holder.itemView.setOnLongClickListener(null)
        } else {
            // 일반 모드
            holder.btnSet.visibility = View.VISIBLE
            holder.btnSet.text = "시간 설정"
            val isDone = isDone(app.packageName)
            holder.tvCheck.visibility = if (isDone) View.VISIBLE else View.INVISIBLE
            holder.tvCheck.text = "✓"
            holder.tvCheck.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            holder.tvName.setTextColor(
                if (isDone) android.graphics.Color.parseColor("#4CAF50")
                else {
                    val attrs = intArrayOf(android.R.attr.textColorPrimary)
                    val ta = context.obtainStyledAttributes(attrs)
                    val color = ta.getColor(0, android.graphics.Color.BLACK)
                    ta.recycle()
                    color
                }
            )

            val displayTime = pendingChanges[app.packageName] ?: run {
                val (h, m) = getSavedResetTime(app.packageName)
                if (h >= 0) Pair(h, m) else null
            }
            val displayDays = if (category == "Weekly") {
                pendingDays[app.packageName] ?: getSavedResetDays(app.packageName)
            } else null
            holder.tvResetTime.text = if (displayTime != null) {
                val dayPrefix = if (displayDays != null && displayDays.isNotEmpty()) getDaysLabel(displayDays) + " " else ""
                "$dayPrefix${String.format("%02d:%02d", displayTime.first, displayTime.second)}"
            } else "미설정"
            if (category == "Event") holder.tvResetTime.text = EventDeadline.label(context, app.packageName)
            holder.tvResetTime.setTextColor(
                if (pendingChanges.containsKey(app.packageName))
                    android.graphics.Color.parseColor("#FFA000")
                else android.graphics.Color.parseColor("#888888")
            )

            holder.btnSet.setOnClickListener { if (category == "Event") onTaskClick(app) else showTimePicker(app.packageName) }
            holder.itemView.setOnClickListener { onTaskClick(app) }
            holder.itemView.setOnLongClickListener { onRemove(app); true }
        }
    }

    // ─── 선택 모드 진입/종료 ──────────────────────────────────────────
    fun enterSelectionMode() {
        isSelectionMode = true
        selectedPackages.clear()
        onSelectionChanged(true)
        notifyDataSetChanged()
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedPackages.clear()
        onSelectionChanged(false)
        notifyDataSetChanged()
    }

    // ─── 정렬 ────────────────────────────────────────────────────────
    fun setSortMode(mode: Int) {
        sortMode = mode
        context.getSharedPreferences("sort_prefs", Context.MODE_PRIVATE)
            .edit().putInt("sort_mode", mode).apply()
        applySortMode()
        notifyDataSetChanged()
    }

    private fun applySortMode() {
        if (sortMode == SORT_NAME) {
            appList.sortBy { it.name }
        } else if (category == "Event") {
            appList.sortBy { EventDeadline.get(context, it.packageName).takeIf { time -> time > 0 } ?: Long.MAX_VALUE }
        } else {
            appList.sortWith(Comparator { a, b ->
                val (ah, am) = getSavedResetTime(a.packageName)
                val (bh, bm) = getSavedResetTime(b.packageName)
                val aMin = if (ah >= 0) ah * 60 + am else Int.MAX_VALUE
                val bMin = if (bh >= 0) bh * 60 + bm else Int.MAX_VALUE
                aMin - bMin
            })
        }
    }

    fun refreshSort() = applySortMode()

    fun showTimePicker(pkg: String) {
        if (category == "Event") {
            showEventEditor(listOf(pkg), appList.firstOrNull { it.packageName == pkg }?.name ?: pkg)
            return
        }
        showResetEditor(listOf(pkg), appList.firstOrNull { it.packageName == pkg }?.name ?: pkg)
    }

    fun showBulkTimePicker() {
        if (selectedPackages.isEmpty()) return
        val packages = selectedPackages.toList().sorted()
        if (category == "Event") {
            showEventEditor(packages, "선택한 앱 ${packages.size}개에 같은 마감을 설정합니다.")
            return
        }
        showResetEditor(packages, context.getString(R.string.reset_bulk_target, packages.size))
    }

    private fun showEventEditor(packages: List<String>, target: String) {
        EventDeadlineDialog.show(context, "$target · 모든 이벤트에 적용", EventDeadline.get(context, packages.first())) { duration ->
            EventDeadline.save(context, packages, duration)
            refreshSort()
            notifyDataSetChanged()
        }
    }

    private fun showResetEditor(packages: List<String>, target: String) {
        val first = packages.first()
        val initial = pendingChanges[first] ?: getSavedResetTime(first).let { if (it.first >= 0) it else 0 to 0 }
        ResetTimeDialog.show(context, category, target, initial.first, initial.second,
            pendingDays[first] ?: getSavedResetDays(first)) { hour, minute, days ->
            val valid = TaskRepository(context).packages(category)
            for (pkg in packages) {
                if (pkg !in valid) continue
                saveResetTime(pkg, hour, minute)
                if (category == "Weekly") saveResetDays(pkg, days)
                ResetScheduler.schedule(context, pkg, category)
                pendingChanges.remove(pkg)
                pendingDays.remove(pkg)
            }
            persistDraft()
            applySortMode()
            notifyDataSetChanged()
            HomeworkWidget.updateAllWidgets(context)
            MiniWidget.updateAllWidgets(context)
            SmallWidget.updateAllWidgets(context)
            android.widget.Toast.makeText(context, R.string.reset_saved, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ─── 실제 저장 ───────────────────────────────────────────────────
    fun commitChanges() {
        val valid = TaskRepository(context).packages(category)
        for ((pkg, time) in pendingChanges) {
            if (pkg !in valid || category == "Event") continue
            saveResetTime(pkg, time.first, time.second)
            if (category == "Weekly") saveResetDays(pkg, pendingDays[pkg] ?: getSavedResetDays(pkg))
            ResetScheduler.schedule(context, pkg, category)
        }
        pendingChanges.clear()
        pendingDays.clear()
        persistDraft()
        applySortMode()
        notifyDataSetChanged()
    }

    fun discardChanges() {
        pendingChanges.clear()
        pendingDays.clear()
        persistDraft()
        notifyDataSetChanged()
    }

    fun hasChanges() = pendingChanges.isNotEmpty() || pendingDays.isNotEmpty()

    private fun isDone(packageName: String) = TaskRepository(context).isDone(packageName, category)

    // ─── SharedPreferences ───────────────────────────────────────────
    private fun saveResetTime(packageName: String, hour: Int, minute: Int) {
        context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
            .edit()
            .putInt("${packageName}_${category}_hour", hour)
            .putInt("${packageName}_${category}_minute", minute)
            .apply()
    }

    fun getSavedResetTime(packageName: String): Pair<Int, Int> {
        val prefs = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
        return Pair(prefs.getInt("${packageName}_${category}_hour", -1), prefs.getInt("${packageName}_${category}_minute", 0))
    }

    fun getSavedResetDays(packageName: String): Set<Int> {
        val str = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
            .getString("${packageName}_${category}_days", "") ?: ""
        return str.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }
            .toSet().ifEmpty { setOf(java.util.Calendar.MONDAY) }
    }

    private fun saveResetDays(packageName: String, days: Set<Int>) {
        context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
            .edit().putString("${packageName}_${category}_days", days.joinToString(",")).apply()
    }

    private fun getDaysLabel(days: Set<Int>): String {
        val map = mapOf(1 to "일", 2 to "월", 3 to "화", 4 to "수", 5 to "목", 6 to "금", 7 to "토")
        return days.sorted().mapNotNull { map[it] }.joinToString("")
    }
}

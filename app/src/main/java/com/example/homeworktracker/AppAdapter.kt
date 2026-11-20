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

    private val collapsed = mutableSetOf<String>()
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
        val header: View = view.findViewById(R.id.appCardHeader)
        val menuAnchor: View = view.findViewById(R.id.appCardMenuAnchor)
        var menuX = 0f
        var menuY = 0f
        val expand: TextView = view.findViewById(R.id.appCardExpand)
        val tasks: android.widget.LinearLayout = view.findViewById(R.id.appCardTasks)
        val complete: TextView = view.findViewById(R.id.appCardComplete)
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
        holder.ivIcon.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#21262D"))
            cornerRadius = 8 * context.resources.displayMetrics.density
            setStroke((context.resources.displayMetrics.density).toInt().coerceAtLeast(1), android.graphics.Color.parseColor("#30363D"))
        }
        holder.ivIcon.clipToOutline = true
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
            holder.btnSet.visibility = View.GONE
            holder.btnSet.text = "⋮"
            val isDone = isDone(app.packageName)
            holder.tvCheck.visibility = View.GONE
            holder.tvCheck.text = "✓"
            holder.tvCheck.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            holder.tvName.setTextColor(android.graphics.Color.parseColor("#F0F6FC"))
            holder.complete.visibility = if (isDone && TaskRepository(context).tasks(app.packageName, category).isNotEmpty()) View.VISIBLE else View.GONE

            val displayTime = if (category == "Event") {
                val prefs = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
                prefs.getInt("${app.packageName}_Daily_hour", -1).takeIf { it >= 0 }?.let { it to prefs.getInt("${app.packageName}_Daily_minute", 0) }
            } else pendingChanges[app.packageName] ?: getSavedResetTime(app.packageName).takeIf { it.first >= 0 }
            holder.tvResetTime.text = if (displayTime != null) "초기화 ${String.format("%02d:%02d", displayTime.first, displayTime.second)}" else "초기화 미설정"
            holder.tvResetTime.setTextColor(
                if (pendingChanges.containsKey(app.packageName))
                    android.graphics.Color.parseColor("#FFA000")
                else android.graphics.Color.parseColor("#888888")
            )

            val items = TaskRepository(context).tasks(app.packageName, category)
            holder.tvResetTime.append(" · ${items.count { it.done }}/${items.size} 완료")
            holder.btnSet.setOnClickListener { anchor ->
                android.widget.PopupMenu(context, holder.menuAnchor).apply {
                    menu.add("할 일 목록 편집")
                    if (category != "Event") menu.add("시간 설정")
                    menu.add("앱 삭제")
                    setOnMenuItemClickListener {
                        when (it.title.toString()) {
                            "시간 설정" -> showTimePicker(app.packageName)
                            "앱 삭제" -> onRemove(app)
                            else -> onTaskClick(app)
                        }
                        true
                    }
                    show()
                }
            }
            holder.itemView.setOnClickListener {
                if (!collapsed.add(app.packageName)) collapsed.remove(app.packageName)
                notifyItemChanged(holder.bindingAdapterPosition)
            }
            holder.itemView.setOnLongClickListener { onRemove(app); true }
        }
        holder.header.setOnClickListener { holder.itemView.performClick() }
        holder.header.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                holder.menuX = event.x
                holder.menuY = event.y
            }
            false
        }
        holder.header.setOnLongClickListener {
            if (isSelectionMode) false else {
                holder.menuAnchor.translationX = holder.menuX.coerceIn(0f, (holder.header.width - 1).coerceAtLeast(0).toFloat())
                holder.menuAnchor.translationY = holder.menuY.coerceIn(0f, (holder.header.height - 1).coerceAtLeast(0).toFloat())
                holder.btnSet.performClick()
            }
        }
        holder.expand.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
        val expanded = app.packageName !in collapsed && !isSelectionMode
        holder.expand.text = ""
        holder.expand.setCompoundDrawablesWithIntrinsicBounds(if (expanded) R.drawable.ic_card_chevron_up else R.drawable.ic_card_chevron_down, 0, 0, 0)
        holder.expand.compoundDrawablePadding = 0
        holder.expand.setPadding((14 * context.resources.displayMetrics.density).toInt(), 0, 0, 0)
        if (isSelectionMode) holder.complete.visibility = View.GONE
        holder.expand.setOnClickListener { holder.header.performClick() }
        holder.header.contentDescription = "${app.name}, ${if (expanded) "접기" else "펼치기"}"
        holder.tasks.removeAllViews()
        holder.tasks.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) bindTasks(holder, app)
    }

    private val delayedRanks = mutableMapOf<String, Pair<Boolean, Long>>()
    internal fun orderedTasks(tasks: List<TaskItem>, now: Long = System.currentTimeMillis()): List<TaskItem> {
        fun rank(task: TaskItem) = delayedRanks[task.id]?.takeIf { now < it.second }?.first ?: task.done
        return tasks.sortedWith(compareBy<TaskItem> { rank(it) }.thenBy {
            if (sortMode == SORT_TIME) it.deadline.takeIf { time -> time > 0 } ?: Long.MAX_VALUE else 0L
        }.thenBy { it.title })
    }

    private fun showCardEditor(app: AppInfo, task: TaskItem) {
        val repository = TaskRepository(context)
        val content = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        val input = android.widget.EditText(context).apply { setText(task.title); setSingleLine(true); hint = "제목" }
        content.addView(input)
        var edited = task
        if (category == "Event") content.addView(com.google.android.material.button.MaterialButton(context).apply {
            text = "시간 설정"
            setOnClickListener {
                val minute = task.resetMinute.takeIf { it >= 0 } ?: EventDeadline.resetMinute(context, app.packageName)
                EventDeadlineDialog.show(context, input.text.toString(), edited.deadline, resetMinute = minute, fixed = task.resetMinute >= 0,
                    onDateSave = { deadline -> edited = edited.copy(deadline = deadline, routineDays = if (task.resetMinute >= 0) edited.routineDays else 0L) },
                    onRoutine = { deadline, period -> edited = edited.copy(deadline = deadline, routineDays = period) }) { duration ->
                    edited = edited.copy(deadline = duration?.let { EventDeadline.calculate(System.currentTimeMillis(), it, minute, task.resetMinute >= 0) } ?: 0L,
                        routineDays = if (task.resetMinute >= 0) edited.routineDays else 0L)
                }
            }
        })
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context).setTitle("일정 편집").setView(content)
            .setNegativeButton("취소", null).setPositiveButton("저장", null).create().apply {
                setOnShowListener {
                    getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val title = input.text.toString().trim()
                        if (title.isEmpty()) { input.error = "제목을 입력해 주세요"; return@setOnClickListener }
                        repository.saveTasks(app.packageName, category, repository.tasks(app.packageName, category).map { if (it.id == task.id) edited.copy(title = title) else it })
                        dismiss()
                    }
                }
                show()
            }
    }

    private fun bindTasks(holder: AppViewHolder, app: AppInfo) {
        val repository = TaskRepository(context)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        for (task in orderedTasks(repository.tasks(app.packageName, category))) {
            holder.tasks.addView(View(context).apply {
                setBackgroundColor(android.graphics.Color.parseColor(if (holder.tasks.childCount == 0) "#30363D" else "#9930363D"))
            }, android.widget.LinearLayout.LayoutParams(-1, dp(1)))
            val row = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(8), dp(6), dp(4), dp(6))
                setBackgroundColor(android.graphics.Color.parseColor("#660D1117"))
                foreground = if (category == "Event" && task.resetMinute in 0..1439) context.getDrawable(R.drawable.event_fixed_time_border) else null
            }
            val check = androidx.appcompat.widget.AppCompatCheckBox(context).apply {
                buttonTintList = null
                setButtonDrawable(R.drawable.card_checkbox)
                background = null
                isChecked = task.done
                contentDescription = "${task.title} 완료" + if (category == "Event" && task.resetMinute in 0..1439) ", 공통 초기화 시간 고정" else ""
                minimumWidth = dp(44); minimumHeight = dp(44)
                setOnCheckedChangeListener { _, done ->
                    val now = System.currentTimeMillis()
                    val previous = delayedRanks[task.id]
                    delayedRanks[task.id] = (previous?.takeIf { now < it.second }?.first ?: task.done) to (now + 300_000L)
                    holder.itemView.postDelayed({ notifyDataSetChanged() }, 300_000L)
                    repository.setTaskDone(app.packageName, category, task.id, done, asyncRefresh = true)
                }
            }
            row.addView(check, android.widget.LinearLayout.LayoutParams(dp(44), dp(44)))
            val labels = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }
            labels.addView(TextView(context).apply {
                text = task.title; textSize = 16f
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(android.graphics.Color.parseColor(if (task.done) "#8B949E" else "#F0F6FC"))
                if (task.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            })
            if (category == "Event") labels.addView(TextView(context).apply {
                text = when {
                    task.deadline <= 0 -> "시간 미설정"
                    task.deadline <= System.currentTimeMillis() -> "종료됨"
                    else -> EventDeadline.format(task.deadline, includeYear = false) + " 종료"
                }; textSize = 12f
                setTextColor(com.google.android.material.color.MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            row.addView(labels, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            labels.setOnClickListener { check.performClick() }
            row.setOnClickListener { check.performClick() }
            row.addView(TextView(context).apply {
                text = "⋮"; textSize = 22f; gravity = android.view.Gravity.CENTER
                contentDescription = "${task.title} 메뉴"
                isFocusable = true
                setOnClickListener { anchor ->
                    android.widget.PopupMenu(context, anchor).apply {
                        menu.add("편집")
                        menu.add("삭제")
                        setOnMenuItemClickListener {
                            when (it.title.toString()) {
                                "편집" -> showCardEditor(app, task)
                                "삭제" -> repository.saveTasks(app.packageName, category, repository.tasks(app.packageName, category).filter { it.id != task.id })
                            }
                            true
                        }
                        show()
                    }
                }
            }, android.widget.LinearLayout.LayoutParams(dp(44), dp(48)))
            holder.tasks.addView(row)
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
            showEventEditor(packages, "선택한 앱 ${packages.size}개에 같은 종료 시간을 설정합니다.")
            return
        }
        showResetEditor(packages, context.getString(R.string.reset_bulk_target, packages.size))
    }

    private fun showEventEditor(packages: List<String>, target: String) {
        EventDeadlineDialog.show(context, "$target · 모든 이벤트에 적용", EventDeadline.get(context, packages.first()),
            resetMinute = EventDeadline.resetMinute(context, packages.first()),
            onDateSave = { deadline ->
                val repository = TaskRepository(context)
                for (pkg in packages.filter { it in repository.packages("Event") }) {
                    repository.saveTasks(pkg, "Event", repository.tasks(pkg, "Event").map { EventResetTime.apply(it.copy(deadline = deadline)) })
                }
                refreshSort()
                notifyDataSetChanged()
            }) { duration ->
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

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
            holder.tvResetTime.setTextColor(
                if (pendingChanges.containsKey(app.packageName))
                    android.graphics.Color.parseColor("#FFA000")
                else android.graphics.Color.parseColor("#888888")
            )

            holder.btnSet.setOnClickListener { showTimePicker(app.packageName, holder.tvResetTime) }
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

    // ─── 시간 선택 다이얼로그 ────────────────────────────────────────
    fun showTimePicker(pkg: String, tvResetTime: TextView? = null) {
        val inflater = LayoutInflater.from(context)
        val dialogView = inflater.inflate(R.layout.dialog_time_picker, null)

        val etHour = dialogView.findViewById<android.widget.EditText>(R.id.etHour)
        val etMinute = dialogView.findViewById<android.widget.EditText>(R.id.etMinute)
        val spinnerHour = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerHour)
        val spinnerMinute = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerMinute)

        spinnerHour.adapter = android.widget.ArrayAdapter(context,
            android.R.layout.simple_spinner_dropdown_item,
            (0..23).map { String.format("%02d시", it) })
        spinnerMinute.adapter = android.widget.ArrayAdapter(context,
            android.R.layout.simple_spinner_dropdown_item,
            (0..59).map { String.format("%02d분", it) })

        val initTime = pendingChanges[pkg] ?: run {
            val (h, m) = getSavedResetTime(pkg)
            if (h >= 0) Pair(h, m) else Pair(0, 0)
        }
        etHour.setText(String.format("%02d", initTime.first))
        etMinute.setText(String.format("%02d", initTime.second))
        spinnerHour.setSelection(initTime.first)
        spinnerMinute.setSelection(initTime.second)

        spinnerHour.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { etHour.setText(String.format("%02d", pos)) }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        spinnerMinute.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { etMinute.setText(String.format("%02d", pos)) }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        etHour.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val h = s.toString().toIntOrNull() ?: return
                if (h in 0..23) spinnerHour.setSelection(h)
            }
        })
        etMinute.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val m = s.toString().toIntOrNull() ?: return
                if (m in 0..59) spinnerMinute.setSelection(m)
            }
        })

        // Weekly 카테고리일 때 요일 선택 영역 표시
        val layoutDayPicker = dialogView.findViewById<android.view.View>(R.id.layoutDayPicker)
        val checkBoxes = listOf(
            dialogView.findViewById<android.widget.CheckBox>(R.id.cbSun),
            dialogView.findViewById<android.widget.CheckBox>(R.id.cbMon),
            dialogView.findViewById<android.widget.CheckBox>(R.id.cbTue),
            dialogView.findViewById<android.widget.CheckBox>(R.id.cbWed),
            dialogView.findViewById<android.widget.CheckBox>(R.id.cbThu),
            dialogView.findViewById<android.widget.CheckBox>(R.id.cbFri),
            dialogView.findViewById<android.widget.CheckBox>(R.id.cbSat)
        )
        val calDays = intArrayOf(1, 2, 3, 4, 5, 6, 7)

        if (category == "Weekly") {
            layoutDayPicker.visibility = View.VISIBLE
            val savedDays = pendingDays[pkg] ?: getSavedResetDays(pkg)
            checkBoxes.forEachIndexed { i, cb ->
                cb.isChecked = calDays[i] in savedDays
                cb.setTextColor(android.graphics.Color.parseColor(
                    if (cb.isChecked) "#2F81F7" else "#8B949E"
                ))
                cb.setOnCheckedChangeListener { _, checked ->
                    cb.setTextColor(android.graphics.Color.parseColor(
                        if (checked) "#2F81F7" else "#8B949E"
                    ))
                }
            }
        }

        android.app.AlertDialog.Builder(context)
            .setView(dialogView)
            .setPositiveButton("확인") { _, _ ->
                val hour = etHour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: spinnerHour.selectedItemPosition
                val minute = etMinute.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: spinnerMinute.selectedItemPosition
                pendingChanges[pkg] = Pair(hour, minute)
                val timeStr = String.format("%02d:%02d", hour, minute)
                if (category == "Weekly") {
                    val selectedDays = calDays.filterIndexed { i, _ -> checkBoxes[i].isChecked }.toSet()
                    pendingDays[pkg] = selectedDays
                    val dayPrefix = if (selectedDays.isNotEmpty()) getDaysLabel(selectedDays) + " " else ""
                    tvResetTime?.text = "$dayPrefix$timeStr"
                } else {
                    tvResetTime?.text = timeStr
                }
                tvResetTime?.setTextColor(android.graphics.Color.parseColor("#FFA000"))
                notifyDataSetChanged()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // 단체 시간 설정
    fun showBulkTimePicker() {
        if (selectedPackages.isEmpty()) return
        // 첫 번째 선택 앱 pkg로 다이얼로그 띄우되, 확인 시 선택된 모든 앱에 적용
        val inflater = LayoutInflater.from(context)
        val dialogView = inflater.inflate(R.layout.dialog_time_picker, null)
        val etHour = dialogView.findViewById<android.widget.EditText>(R.id.etHour)
        val etMinute = dialogView.findViewById<android.widget.EditText>(R.id.etMinute)
        val spinnerHour = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerHour)
        val spinnerMinute = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerMinute)

        spinnerHour.adapter = android.widget.ArrayAdapter(context,
            android.R.layout.simple_spinner_dropdown_item,
            (0..23).map { String.format("%02d시", it) })
        spinnerMinute.adapter = android.widget.ArrayAdapter(context,
            android.R.layout.simple_spinner_dropdown_item,
            (0..59).map { String.format("%02d분", it) })

        etHour.setText("00"); etMinute.setText("00")

        spinnerHour.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { etHour.setText(String.format("%02d", pos)) }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        spinnerMinute.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { etMinute.setText(String.format("%02d", pos)) }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        android.app.AlertDialog.Builder(context)
            .setTitle("선택한 앱 시간 설정 (${selectedPackages.size}개)")
            .setView(dialogView)
            .setPositiveButton("확인") { _, _ ->
                val hour = etHour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: spinnerHour.selectedItemPosition
                val minute = etMinute.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: spinnerMinute.selectedItemPosition
                for (pkg in selectedPackages) {
                    pendingChanges[pkg] = Pair(hour, minute)
                }
                notifyDataSetChanged()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ─── 실제 저장 ───────────────────────────────────────────────────
    fun commitChanges() {
        for ((pkg, time) in pendingChanges) {
            saveResetTime(pkg, time.first, time.second)
            HomeworkWidget.scheduleResetIfNeeded(context, pkg, category)
        }
        for ((pkg, days) in pendingDays) {
            saveResetDays(pkg, days)
            HomeworkWidget.scheduleResetIfNeeded(context, pkg, category)
        }
        pendingChanges.clear()
        pendingDays.clear()
        applySortMode()
        notifyDataSetChanged()
    }

    fun discardChanges() {
        pendingChanges.clear()
        pendingDays.clear()
        notifyDataSetChanged()
    }

    fun hasChanges() = pendingChanges.isNotEmpty()

    // ─── 완료 여부 ────────────────────────────────────────────────────
    fun setDone(packageName: String, done: Boolean) {
        context.getSharedPreferences("done_status", Context.MODE_PRIVATE)
            .edit().putBoolean(packageName, done).apply()
    }

    private fun isDone(packageName: String) =
        context.getSharedPreferences("done_status", Context.MODE_PRIVATE)
            .getBoolean(packageName, false)

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
        return if (str.isEmpty()) emptySet()
        else str.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
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
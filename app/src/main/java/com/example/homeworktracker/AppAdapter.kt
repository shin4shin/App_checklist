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
    private val onSelectionChanged: (Boolean) -> Unit  // 선택 모드 on/off 콜백
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val SORT_NAME = 0
        const val SORT_TIME = 1
    }

    val pendingChanges = mutableMapOf<String, Pair<Int, Int>>()

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
            holder.tvResetTime.text = if (displayTime != null)
                String.format("%02d:%02d", displayTime.first, displayTime.second)
            else "미설정"
            holder.tvResetTime.setTextColor(
                if (pendingChanges.containsKey(app.packageName))
                    android.graphics.Color.parseColor("#FFA000")
                else android.graphics.Color.parseColor("#888888")
            )

            holder.btnSet.setOnClickListener { showTimePicker(app.packageName, holder.tvResetTime) }
            holder.itemView.setOnClickListener(null)
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

        android.app.AlertDialog.Builder(context)
            .setView(dialogView)
            .setPositiveButton("확인") { _, _ ->
                val hour = etHour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: spinnerHour.selectedItemPosition
                val minute = etMinute.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: spinnerMinute.selectedItemPosition
                pendingChanges[pkg] = Pair(hour, minute)
                tvResetTime?.text = String.format("%02d:%02d", hour, minute)
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
            if (isDone(pkg)) HomeworkWidget.scheduleResetIfNeeded(context, pkg)
        }
        pendingChanges.clear()
        applySortMode()
        notifyDataSetChanged()
    }

    fun discardChanges() {
        pendingChanges.clear()
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
            .putInt("${packageName}_hour", hour)
            .putInt("${packageName}_minute", minute)
            .apply()
    }

    fun getSavedResetTime(packageName: String): Pair<Int, Int> {
        val prefs = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
        return Pair(prefs.getInt("${packageName}_hour", -1), prefs.getInt("${packageName}_minute", 0))
    }
}
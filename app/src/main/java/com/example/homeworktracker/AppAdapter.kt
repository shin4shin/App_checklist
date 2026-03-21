// AppAdapter.kt
package com.example.homeworktracker

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val context: Context,
    private val appList: MutableList<AppInfo>,
    private val onRemove: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvCheck: TextView = view.findViewById(R.id.tvCheck)
        val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvName: TextView = view.findViewById(R.id.tvAppName)
        val tvResetTime: TextView = view.findViewById(R.id.tvResetTime)
        val btnSet: Button = view.findViewById(R.id.btnSetTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = appList[position]

        holder.ivIcon.setImageDrawable(app.icon)
        holder.tvName.text = app.name

        // 완료 여부 체크 표시
        val isDone = isDone(app.packageName)
        holder.tvCheck.visibility = if (isDone) View.VISIBLE else View.INVISIBLE

        // 완료된 앱은 이름 색상도 변경 (테마 색상 따름)
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

        // 저장된 초기화 시간 표시
        val (savedHour, savedMinute) = getResetTime(app.packageName)
        holder.tvResetTime.text = if (savedHour >= 0) String.format("%02d:%02d", savedHour, savedMinute) else "미설정"

        // [설정] 버튼 → 드롭다운 다이얼로그
        holder.btnSet.setOnClickListener {
            showTimePicker(app.packageName, holder.tvResetTime)
        }

        // 길게 누르면 삭제
        holder.itemView.setOnLongClickListener {
            onRemove(app)
            true
        }
    }

    override fun getItemCount() = appList.size

    // ─── 시간 선택 다이얼로그 (드롭다운 + 직접 입력) ────────────────
    private fun showTimePicker(packageName: String, tvResetTime: TextView) {
        val inflater = LayoutInflater.from(context)
        val dialogView = inflater.inflate(R.layout.dialog_time_picker, null)

        val etHour = dialogView.findViewById<android.widget.EditText>(R.id.etHour)
        val etMinute = dialogView.findViewById<android.widget.EditText>(R.id.etMinute)
        val spinnerHour = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerHour)
        val spinnerMinute = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerMinute)

        val hours = (0..23).map { String.format("%02d시", it) }
        val minuteList = (0..59).map { String.format("%02d분", it) }

        spinnerHour.adapter = android.widget.ArrayAdapter(context,
            android.R.layout.simple_spinner_dropdown_item, hours)
        spinnerMinute.adapter = android.widget.ArrayAdapter(context,
            android.R.layout.simple_spinner_dropdown_item, minuteList)

        // 기존 저장된 시간으로 초기값 설정
        val (savedHour, savedMinute) = getResetTime(packageName)
        val initHour = if (savedHour >= 0) savedHour else 0
        val initMinute = if (savedMinute >= 0) savedMinute else 0
        etHour.setText(String.format("%02d", initHour))
        etMinute.setText(String.format("%02d", initMinute))
        spinnerHour.setSelection(initHour)
        spinnerMinute.setSelection(initMinute)

        // 스피너 선택 시 EditText에도 반영
        spinnerHour.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                etHour.setText(String.format("%02d", pos))
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        spinnerMinute.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                etMinute.setText(String.format("%02d", pos))
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        // EditText 입력 시 스피너에도 반영
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
                saveResetTime(packageName, hour, minute)
                tvResetTime.text = String.format("%02d:%02d", hour, minute)
                (context as MainActivity).scheduleReset(packageName, hour, minute)
                Toast.makeText(context, "${String.format("%02d:%02d", hour, minute)}에 초기화 예약됨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ─── 완료 여부 저장/불러오기 ──────────────────────────────────────
    fun setDone(packageName: String, done: Boolean) {
        context.getSharedPreferences("done_status", Context.MODE_PRIVATE)
            .edit().putBoolean(packageName, done).apply()
    }

    private fun isDone(packageName: String): Boolean {
        return context.getSharedPreferences("done_status", Context.MODE_PRIVATE)
            .getBoolean(packageName, false)
    }

    // ─── SharedPreferences: 초기화 시간 저장/불러오기 ─────────────────
    private fun saveResetTime(packageName: String, hour: Int, minute: Int) {
        context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
            .edit()
            .putInt("${packageName}_hour", hour)
            .putInt("${packageName}_minute", minute)
            .apply()
    }

    private fun getResetTime(packageName: String): Pair<Int, Int> {
        val prefs = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
        val hour = prefs.getInt("${packageName}_hour", -1)
        val minute = prefs.getInt("${packageName}_minute", 0)
        return Pair(hour, minute)
    }
}
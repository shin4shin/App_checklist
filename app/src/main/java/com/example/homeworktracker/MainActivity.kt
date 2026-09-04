// MainActivity.kt
package com.example.homeworktracker

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable
)

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private val appList = mutableListOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (intent.action == "com.example.homeworktracker.HOMEWORK_DONE") {
            showHomeworkDoneDialog()
            return
        }

        setupRecyclerView()
        setupFab()
        setupMenu()
        setupSelectionBar()
        setupSaveButton()
        setupBackPress()
    }

    // ─── 저장 버튼 ────────────────────────────────────────────────────
    private fun setupSaveButton() {
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            if (adapter.hasChanges()) saveAll()
            else Toast.makeText(this, "변경된 내용이 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── 뒤로가기 처리 ───────────────────────────────────────────────
    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.isSelectionMode) {
                    adapter.exitSelectionMode()
                    findViewById<LinearLayout>(R.id.layoutSelectionBar).visibility = View.GONE
                    return
                }
                if (adapter.hasChanges()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("저장하지 않은 변경 사항이 있습니다.\n저장하시겠습니까?")
                        .setPositiveButton("네") { _, _ -> saveAll(); finish() }
                        .setNegativeButton("아니오") { _, _ -> adapter.discardChanges(); finish() }
                        .show()
                } else {
                    finish()
                }
            }
        })
    }

    private fun saveAll() {
        adapter.commitChanges()
        HomeworkWidget.updateAllWidgets(this)
        MiniWidget.updateAllWidgets(this)
        SmallWidget.updateAllWidgets(this)
        Toast.makeText(this, "저장되었습니다", Toast.LENGTH_SHORT).show()
    }

    // ─── 완료할 앱 선택 다이얼로그 ───────────────────────────────────
    private fun showHomeworkDoneDialog() {
        val addedApps = getAddedAppList()

        if (addedApps.isEmpty()) {
            Toast.makeText(this, "추가된 앱이 없어요. 먼저 앱을 추가해주세요!", Toast.LENGTH_SHORT).show()
            setupRecyclerView(); setupFab(); setupSaveButton(); setupBackPress()
            return
        }

        val appNames = addedApps.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("어떤 앱 숙제를 완료했나요?")
            .setItems(appNames) { _, index ->
                val selected = addedApps[index]
                markHomeworkDone(selected.packageName)
                Toast.makeText(this, "${selected.name} 숙제 완료! ✓", Toast.LENGTH_SHORT).show()
                setupRecyclerView(); setupFab(); setupSaveButton(); setupBackPress()
            }
            .setNegativeButton("취소") { _, _ ->
                setupRecyclerView(); setupFab(); setupSaveButton(); setupBackPress()
            }
            .show()
    }

    // ─── 숙제 완료 처리 ──────────────────────────────────────────────
    private fun markHomeworkDone(targetPackage: String) {
        setDoneStatus(targetPackage, true)
        val prefs = getSharedPreferences("reset_times", MODE_PRIVATE)
        val hour = prefs.getInt("${targetPackage}_hour", -1)
        val minute = prefs.getInt("${targetPackage}_minute", 0)
        if (hour >= 0) scheduleReset(targetPackage, hour, minute)
        HomeworkWidget.updateAllWidgets(this)
    }

    fun setDoneStatus(pkg: String, done: Boolean) {
        getSharedPreferences("done_status", Context.MODE_PRIVATE)
            .edit().putBoolean(pkg, done).apply()
    }

    // ─── AlarmManager: 초기화 예약 ───────────────────────────────────
    fun scheduleReset(targetPackage: String, hour: Int, minute: Int = 0) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= Calendar.getInstance().timeInMillis) add(Calendar.DATE, 1)
        }
        val intent = Intent(this, ResetReceiver::class.java).apply {
            putExtra("target_package", targetPackage)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, targetPackage.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms())
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            else
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        }
    }

    fun cancelReset(targetPackage: String) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ResetReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, targetPackage.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    // ─── 앱 추가 다이얼로그 ───────────────────────────────────────────
    private fun showAppPickerDialog() {
        val addedPackages = getAddedAppPackages()
        val installedApps = getAllInstalledApps().filter { it.packageName !in addedPackages }

        if (installedApps.isEmpty()) {
            Toast.makeText(this, "추가할 앱이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val etSearch = dialogView.findViewById<android.widget.EditText>(R.id.etSearch)
        val rvApps = dialogView.findViewById<RecyclerView>(R.id.rvPickerApps)

        val dialog = AlertDialog.Builder(this)
            .setTitle("앱 추가")
            .setView(dialogView)
            .setNegativeButton("취소", null)
            .create()

        val pickerAdapter = AppPickerAdapter(this, installedApps.toMutableList()) { app ->
            saveAddedApp(app.packageName)
            refreshAppList()
            HomeworkWidget.updateAllWidgets(this)
            dialog.dismiss()
        }

        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = pickerAdapter

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { pickerAdapter.filter(s.toString()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        dialog.show()
    }

    // ─── 앱 삭제 ─────────────────────────────────────────────────────
    private fun removeApp(app: AppInfo) {
        AlertDialog.Builder(this)
            .setTitle("앱 삭제")
            .setMessage("${app.name}을(를) 목록에서 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                deleteAddedApp(app.packageName)
                cancelReset(app.packageName)
                refreshAppList()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ─── 점 3개 메뉴 ─────────────────────────────────────────────────
    private fun setupMenu() {
        findViewById<TextView>(R.id.tvMenu).setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menu.add(0, 1, 0, "정렬")
            popup.menu.add(0, 2, 1, "선택")
            popup.menu.add(0, 3, 2, "오버레이 설정")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> showSortDialog()
                    2 -> adapter.enterSelectionMode()
                    3 -> showOverlaySetupDialog()
                }
                true
            }
            popup.show()
        }
    }

    private fun showSortDialog() {
        val options = arrayOf("가나다 순", "시간 순")
        AlertDialog.Builder(this)
            .setTitle("정렬")
            .setSingleChoiceItems(options, adapter.sortMode) { dialog, which ->
                adapter.setSortMode(which)
                HomeworkWidget.updateAllWidgets(this)
                MiniWidget.updateAllWidgets(this)
                SmallWidget.updateAllWidgets(this)
                dialog.dismiss()
            }
            .show()
    }

    // ─── 선택 모드 하단 액션바 ────────────────────────────────────────
    private fun setupSelectionBar() {
        val bar = findViewById<LinearLayout>(R.id.layoutSelectionBar)

        findViewById<Button>(R.id.btnSelectionDelete).setOnClickListener {
            val selected = adapter.selectedPackages.toList()
            if (selected.isEmpty()) { Toast.makeText(this, "선택된 앱이 없습니다", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            AlertDialog.Builder(this)
                .setMessage("선택한 앱 ${selected.size}개를 삭제할까요?")
                .setPositiveButton("삭제") { _, _ ->
                    for (pkg in selected) { deleteAddedApp(pkg); cancelReset(pkg) }
                    adapter.exitSelectionMode()
                    bar.visibility = View.GONE
                    refreshAppList()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        findViewById<Button>(R.id.btnSelectionSetTime).setOnClickListener {
            if (adapter.selectedPackages.isEmpty()) { Toast.makeText(this, "선택된 앱이 없습니다", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            adapter.showBulkTimePicker()
        }

        findViewById<Button>(R.id.btnSelectionCancel).setOnClickListener {
            adapter.exitSelectionMode()
            bar.visibility = View.GONE
        }
    }

    // ─── RecyclerView 셋업 ────────────────────────────────────────────
    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.rvApps)
        val bar = findViewById<LinearLayout>(R.id.layoutSelectionBar)
        val btnSave = findViewById<Button>(R.id.btnSave)
        adapter = AppAdapter(
            context = this,
            appList = appList,
            onRemove = { app -> removeApp(app) },
            onSave = { saveAll() },
            onSelectionChanged = { isSelecting ->
                bar.visibility = if (isSelecting) View.VISIBLE else View.GONE
                btnSave.visibility = if (isSelecting) View.GONE else View.VISIBLE
                findViewById<FloatingActionButton>(R.id.fabAddApp)
                    .visibility = if (isSelecting) View.GONE else View.VISIBLE
            },
            onTaskClick = { app -> showTaskDialog(app) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        refreshAppList()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFab() {
        val fab = findViewById<FloatingActionButton>(R.id.fabAddApp)
        var dX = 0f; var dY = 0f
        var startRawX = 0f; var startRawY = 0f
        var isDragging = false

        fab.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    startRawX = event.rawX
                    startRawY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(event.rawX - startRawX) > 10 || Math.abs(event.rawY - startRawY) > 10) isDragging = true
                    if (isDragging) {
                        val newX = (event.rawX + dX).coerceIn(0f, (resources.displayMetrics.widthPixels - view.width).toFloat())
                        val newY = (event.rawY + dY).coerceIn(0f, (resources.displayMetrics.heightPixels - view.height).toFloat())
                        view.animate().x(newX).y(newY).setDuration(0).start()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) showAppPickerDialog()
                    true
                }
                else -> false
            }
        }
    }

    // ─── SharedPreferences ───────────────────────────────────────────
    private fun saveAddedApp(pkg: String) {
        val prefs = getSharedPreferences("added_apps", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("apps", mutableSetOf())!!.toMutableSet()
        set.add(pkg); prefs.edit().putStringSet("apps", set).apply()
        // 처음 추가하는 앱에만 기본 데일리 태스크 설정
        if (getSharedPreferences("app_tasks", MODE_PRIVATE).getString(pkg, null) == null) {
            saveTasksByCategory(pkg, mutableMapOf("Daily" to mutableListOf("일일 퀘스트")))
        }
    }

    private fun deleteAddedApp(pkg: String) {
        val prefs = getSharedPreferences("added_apps", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("apps", mutableSetOf())!!.toMutableSet()
        set.remove(pkg); prefs.edit().putStringSet("apps", set).apply()
    }

    fun getAddedAppPackages(): Set<String> =
        getSharedPreferences("added_apps", Context.MODE_PRIVATE).getStringSet("apps", emptySet()) ?: emptySet()

    private fun getAddedAppList(): List<AppInfo> {
        val pm = packageManager
        return getAddedAppPackages().mapNotNull { pkg ->
            try { val info = pm.getApplicationInfo(pkg, 0)
                AppInfo(pm.getApplicationLabel(info).toString(), pkg, pm.getApplicationIcon(info))
            } catch (e: Exception) { null }
        }.sortedBy { it.name }
    }

    private fun getAllInstalledApps(): List<AppInfo> {
        val pm = packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .filter { it.packageName != packageName }
            .map { AppInfo(pm.getApplicationLabel(it).toString(), it.packageName, pm.getApplicationIcon(it)) }
            .sortedBy { it.name }
    }

    private fun refreshAppList() {
        appList.clear()
        appList.addAll(getAddedAppList())
        adapter.refreshSort()
        adapter.notifyDataSetChanged()
    }

    // ─── 태스크 다이얼로그 ────────────────────────────────────────────
    private fun showTaskDialog(app: AppInfo) {
        val categoryKeys  = listOf("Daily", "Weekly", "Monthly", "Event")
        val categoryNames = listOf("데일리 (Daily)", "위클리 (Weekly)", "먼슬리 (Monthly)", "이벤트 (Event)")

        val dialogView = layoutInflater.inflate(R.layout.dialog_task_list, null)
        val spinner  = dialogView.findViewById<Spinner>(R.id.spinnerCategory)
        val etNewTask = dialogView.findViewById<EditText>(R.id.etNewTask)
        val btnAdd   = dialogView.findViewById<Button>(R.id.btnAddTask)
        val rvTasks  = dialogView.findViewById<RecyclerView>(R.id.rvTasks)

        val allTasks = loadTasksByCategory(app.packageName)
        val taskMap  = categoryKeys.associateWith { cat ->
            (allTasks[cat] ?: emptyList()).toMutableList()
        }.toMutableMap()

        var currentCategory = categoryKeys[0]
        var currentTasks    = taskMap[currentCategory]!!

        val taskAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = currentTasks.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(layoutInflater.inflate(R.layout.item_task_edit, parent, false)) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                holder.itemView.findViewById<TextView>(R.id.tvTaskItem).text = currentTasks[position]
                holder.itemView.findViewById<TextView>(R.id.btnDeleteTask).setOnClickListener {
                    val pos = holder.adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        currentTasks.removeAt(pos)
                        notifyItemRemoved(pos)
                    }
                }
            }
        }

        rvTasks.layoutManager = LinearLayoutManager(this)
        rvTasks.adapter = taskAdapter

        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categoryNames)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = spinnerAdapter
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                currentCategory = categoryKeys[pos]
                currentTasks = taskMap[currentCategory]!!
                taskAdapter.notifyDataSetChanged()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        btnAdd.setOnClickListener {
            val text = etNewTask.text.toString().trim()
            if (text.isNotEmpty()) {
                currentTasks.add(text)
                taskAdapter.notifyItemInserted(currentTasks.size - 1)
                etNewTask.text.clear()
            }
        }

        AlertDialog.Builder(this)
            .setTitle("${app.name} - 할 일 목록")
            .setView(dialogView)
            .setPositiveButton("저장") { _, _ -> saveTasksByCategory(app.packageName, taskMap) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun loadTasksByCategory(pkg: String): Map<String, List<String>> {
        val json = getSharedPreferences("app_tasks", MODE_PRIVATE).getString(pkg, "{}") ?: "{}"
        return try {
            if (json.trim().startsWith("[")) {
                val arr = JSONArray(json)
                val list = (0 until arr.length()).map { arr.getString(it) }
                if (list.isEmpty()) emptyMap() else mapOf("Daily" to list)
            } else {
                val obj = JSONObject(json)
                val result = mutableMapOf<String, List<String>>()
                for (key in obj.keys()) {
                    val arr = obj.getJSONArray(key)
                    result[key] = (0 until arr.length()).map { arr.getString(it) }
                }
                result
            }
        } catch (e: Exception) { emptyMap() }
    }

    private fun saveTasksByCategory(pkg: String, taskMap: Map<String, MutableList<String>>) {
        val obj = JSONObject()
        for ((category, tasks) in taskMap) {
            if (tasks.isNotEmpty()) obj.put(category, JSONArray(tasks))
        }
        getSharedPreferences("app_tasks", MODE_PRIVATE)
            .edit().putString(pkg, obj.toString()).apply()
    }

    // ─── 오버레이 설정 안내 ────────────────────────────────────────────
    private fun showOverlaySetupDialog() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = isAccessibilityEnabled()

        if (overlayOk && accessibilityOk) {
            Toast.makeText(this, "오버레이 설정이 완료되어 있습니다", Toast.LENGTH_SHORT).show()
            return
        }

        val msg = buildString {
            if (!overlayOk) appendLine("• [다른 앱 위에 표시] 권한이 필요합니다.")
            if (!accessibilityOk) appendLine("• [접근성] 서비스 활성화가 필요합니다.")
        }

        AlertDialog.Builder(this)
            .setTitle("오버레이 설정")
            .setMessage(msg.trim())
            .apply {
                if (!overlayOk) setPositiveButton("다른 앱 위에 표시 설정") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
                if (!accessibilityOk) setNeutralButton("접근성 서비스 설정") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "$packageName/${GameOverlayService::class.java.canonicalName}"
        return try {
            val enabled = Settings.Secure.getInt(contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (enabled == 0) return false
            val services = Settings.Secure.getString(contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            services.split(":").any { it.equals(service, ignoreCase = true) }
        } catch (e: Exception) { false }
    }
}
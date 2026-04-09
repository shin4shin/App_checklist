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
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> showSortDialog()
                    2 -> adapter.enterSelectionMode()
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
            }
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
        appList.clear(); appList.addAll(getAddedAppList()); adapter.notifyDataSetChanged()
    }
}
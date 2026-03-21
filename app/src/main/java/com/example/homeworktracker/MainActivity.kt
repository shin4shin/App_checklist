// MainActivity.kt
package com.example.homeworktracker

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
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

        // 숙제 완료 숏컷으로 실행됐을 때
        if (intent.action == "com.example.homeworktracker.HOMEWORK_DONE") {
            showHomeworkDoneDialog()
            return
        }

        setupRecyclerView()
        setupFab()
    }

    // ─── 완료할 앱 선택 다이얼로그 ───────────────────────────────────
    private fun showHomeworkDoneDialog() {
        val addedApps = getAddedAppList()

        if (addedApps.isEmpty()) {
            Toast.makeText(this, "추가된 앱이 없어요. 먼저 앱을 추가해주세요!", Toast.LENGTH_SHORT).show()
            setupRecyclerView()
            setupFab()
            return
        }

        val appNames = addedApps.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("어떤 앱 숙제를 완료했나요?")
            .setItems(appNames) { _, index ->
                val selected = addedApps[index]
                markHomeworkDone(selected.packageName)
                Toast.makeText(this, "${selected.name} 숙제 완료! ✓", Toast.LENGTH_SHORT).show()
                setupRecyclerView()
                setupFab()
            }
            .setNegativeButton("취소") { _, _ ->
                setupRecyclerView()
                setupFab()
            }
            .show()
    }

    // ─── 숙제 완료 처리 ──────────────────────────────────────────────
    private fun markHomeworkDone(targetPackage: String) {
        // 완료 상태 저장
        setDoneStatus(targetPackage, true)

        // 초기화 알람 예약
        val prefs = getSharedPreferences("reset_times", MODE_PRIVATE)
        val hour = prefs.getInt("${targetPackage}_hour", -1)
        val minute = prefs.getInt("${targetPackage}_minute", 0)
        if (hour >= 0) scheduleReset(targetPackage, hour, minute)

        // 위젯 갱신
        HomeworkWidget.updateAllWidgets(this)
    }

    // ─── 완료 상태 저장 ───────────────────────────────────────────────
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
            if (before(Calendar.getInstance())) add(Calendar.DATE, 1)
        }
        val intent = Intent(this, ResetReceiver::class.java).apply {
            putExtra("target_package", targetPackage)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, targetPackage.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // setRepeating 대신 setExactAndAllowWhileIdle 사용
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pendingIntent
        )
    }

    // ─── AlarmManager: 초기화 취소 ───────────────────────────────────
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
        val rvApps = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvPickerApps)

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
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pickerAdapter.filter(s.toString())
            }
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

    // ─── RecyclerView 셋업 ────────────────────────────────────────────
    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.rvApps)
        adapter = AppAdapter(
            context = this,
            appList = appList,
            onRemove = { app -> removeApp(app) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        refreshAppList()
    }

    // ─── FAB 셋업 ─────────────────────────────────────────────────────
    private fun setupFab() {
        findViewById<FloatingActionButton>(R.id.fabAddApp).setOnClickListener {
            showAppPickerDialog()
        }
    }

    // ─── SharedPreferences: 추가된 앱 목록 관리 ──────────────────────
    private fun saveAddedApp(pkg: String) {
        val prefs = getSharedPreferences("added_apps", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("apps", mutableSetOf())!!.toMutableSet()
        set.add(pkg)
        prefs.edit().putStringSet("apps", set).apply()
    }

    private fun deleteAddedApp(pkg: String) {
        val prefs = getSharedPreferences("added_apps", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("apps", mutableSetOf())!!.toMutableSet()
        set.remove(pkg)
        prefs.edit().putStringSet("apps", set).apply()
    }

    fun getAddedAppPackages(): Set<String> {
        return getSharedPreferences("added_apps", Context.MODE_PRIVATE)
            .getStringSet("apps", emptySet()) ?: emptySet()
    }

    private fun getAddedAppList(): List<AppInfo> {
        val pm = packageManager
        return getAddedAppPackages().mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
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
        adapter.notifyDataSetChanged()
    }
}
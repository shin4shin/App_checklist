package com.example.homeworktracker

import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class HomeActivity : AppCompatActivity() {

    private var currentNavId = R.id.nav_home

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        TaskRepository(this)
        ResetScheduler.restore(this)
        currentNavId = savedInstanceState?.getInt("current_nav", R.id.nav_home)
            ?: navForCategory(intent.getStringExtra("category"))

        val navigation = findViewById<BottomNavigationView>(R.id.bottomNav)
        applyModeNavigation()
        if (PlannerMode.isPersonal(this) && currentNavId !in listOf(R.id.nav_home, R.id.nav_more)) currentNavId = R.id.nav_home
        navigation.selectedItemId = currentNavId

        if (savedInstanceState == null) {
            showFragment(currentNavId)
            if (intent.action == "com.example.homeworktracker.HOMEWORK_DONE") showDonePicker()
            else checkPermissionsOnStart()
        }

        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            currentNavId = item.itemId
            showFragment(item.itemId)
            true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId =
            if (PlannerMode.isPersonal(this)) R.id.nav_home else navForCategory(intent.getStringExtra("category"))
        if (intent.action == "com.example.homeworktracker.HOMEWORK_DONE") showDonePicker()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("current_nav", currentNavId)
        super.onSaveInstanceState(outState)
    }

    private fun navForCategory(category: String?) = when (category) {
        "Daily" -> R.id.nav_daily
        "Weekly" -> R.id.nav_weekly
        "Event" -> R.id.nav_monthly
        else -> R.id.nav_home
    }

    private fun showDonePicker() {
        val repository = TaskRepository(this)
        val apps = repository.packages("Daily").mapNotNull { pkg ->
            try { pkg to packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
        }.sortedBy { it.second }
        if (apps.isEmpty()) return
        AlertDialog.Builder(this).setTitle("어떤 앱 숙제를 완료했나요?")
            .setItems(apps.map { it.second }.toTypedArray()) { _, which ->
                repository.setDone(apps[which].first, "Daily", true)
            }.setNegativeButton("취소", null).show()
    }

    private fun checkPermissionsOnStart() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = isAccessibilityEnabled()
        val alarmOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        else true

        if (overlayOk && accessibilityOk && alarmOk) return

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (!overlayOk) {
            labels.add("다른 앱 위에 표시 설정")
            actions.add {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        if (!accessibilityOk) {
            labels.add("접근성 서비스 설정")
            actions.add { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        if (!alarmOk) {
            labels.add("정확한 알람 설정")
            actions.add {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }

        AlertDialog.Builder(this)
            .setTitle("권한 설정 필요")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "$packageName/${GameOverlayService::class.java.canonicalName}"
        return try {
            val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (enabled == 0) return false
            val services = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            services.split(":").any { it.equals(service, ignoreCase = true) }
        } catch (e: Exception) { false }
    }

    private fun applyModeNavigation() {
        val menu = findViewById<BottomNavigationView>(R.id.bottomNav).menu
        for (id in listOf(R.id.nav_daily, R.id.nav_weekly, R.id.nav_monthly)) {
            menu.findItem(id).isVisible = !PlannerMode.isPersonal(this)
        }
    }

    fun switchPlannerMode() {
        PlannerMode.setPersonal(this, !PlannerMode.isPersonal(this))
        applyModeNavigation()
        currentNavId = R.id.nav_home
        val navigation = findViewById<BottomNavigationView>(R.id.bottomNav)
        if (navigation.selectedItemId == R.id.nav_home) showFragment(R.id.nav_home)
        else navigation.selectedItemId = R.id.nav_home
    }

    private fun showFragment(navId: Int) {
        (supportFragmentManager.findFragmentById(R.id.fragmentContainer) as? DailyFragment)?.persistDraft()
        val fragment: Fragment = if (PlannerMode.isPersonal(this) && navId != R.id.nav_more) PersonalPlansFragment() else when (navId) {
            R.id.nav_home     -> HomeFragment()
            R.id.nav_daily    -> DailyFragment.newInstance("Daily")
            R.id.nav_weekly   -> DailyFragment.newInstance("Weekly")
            R.id.nav_monthly  -> DailyFragment.newInstance("Event")
            R.id.nav_more     -> MoreFragment()
            else              -> HomeFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}

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

        if (savedInstanceState == null) {
            showFragment(R.id.nav_home)
            checkPermissionsOnStart()
        }

        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            currentNavId = item.itemId
            showFragment(item.itemId)
            true
        }
    }

    private fun checkPermissionsOnStart() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = isAccessibilityEnabled()
        val alarmOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        else true

        if (overlayOk && accessibilityOk && alarmOk) return

        val missing = buildString {
            if (!overlayOk) appendLine("• 다른 앱 위에 표시 — 오버레이 표시에 필요")
            if (!accessibilityOk) appendLine("• 접근성 서비스 — 실행 중인 앱 감지에 필요")
            if (!alarmOk) appendLine("• 정확한 알람 — 일일 초기화에 필요")
        }.trimEnd()

        AlertDialog.Builder(this)
            .setTitle("권한 설정 필요")
            .setMessage("아래 권한이 설정되지 않아 일부 기능이 동작하지 않습니다.\n\n$missing")
            .setPositiveButton("설정하러 가기") { _, _ ->
                findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_more
            }
            .setNegativeButton("나중에") { d, _ -> d.dismiss() }
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

    private fun showFragment(navId: Int) {
        val fragment: Fragment = when (navId) {
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

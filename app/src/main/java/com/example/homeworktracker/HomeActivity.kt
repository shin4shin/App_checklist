package com.example.homeworktracker

import android.app.AlertDialog
import android.app.AlarmManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {

    private var permDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<View>(R.id.cardDaily).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        listOf(R.id.cardWeekly, R.id.cardMonthly, R.id.cardEvent, R.id.cardPremium)
            .forEach { id ->
                findViewById<View>(id).setOnClickListener { showInDevelopmentDialog() }
            }

    }

    override fun onResume() {
        super.onResume()
        if (permDialog?.isShowing == true) {
            // 설정 화면에서 돌아왔을 때 갱신
            permDialog?.dismiss()
        }
        if (!allPermissionsGranted()) {
            showPermissionSetupDialog()
        }
    }

    private fun allPermissionsGranted(): Boolean {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = isAccessibilityEnabled()
        val alarmOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        else true
        return overlayOk && accessibilityOk && alarmOk
    }

    // ─── 권한 설정 다이얼로그 ─────────────────────────────────────────
    private fun showPermissionSetupDialog() {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        }

        val desc = TextView(this).apply {
            text = "오버레이 기능을 사용하려면 아래 권한이 필요합니다."
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, (12 * dp).toInt())
        }
        root.addView(desc)

        val permissions = buildList {
            add(Triple(
                "다른 앱 위에 표시",
                "게임 실행 중 오버레이 표시",
                { Settings.canDrawOverlays(this@HomeActivity) }
            ) to {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            })
            add(Triple(
                "접근성 서비스",
                "실행 중인 앱 감지",
                { isAccessibilityEnabled() }
            ) to {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Triple(
                    "정확한 알람",
                    "일일 초기화 시간 정확히 실행",
                    { (getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() }
                ) to {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName")))
                })
            }
        }

        for ((info, action) in permissions) {
            val (name, detail, check) = info
            val isGranted = check()

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
            }

            val statusIcon = TextView(this).apply {
                text = if (isGranted) "✓" else "○"
                textSize = 16f
                setTextColor(if (isGranted) Color.parseColor("#4CAF50") else Color.parseColor("#888888"))
                setPadding(0, 0, (10 * dp).toInt(), 0)
                typeface = Typeface.DEFAULT_BOLD
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvName = TextView(this).apply {
                text = name
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            val tvDetail = TextView(this).apply {
                text = detail
                textSize = 12f
                setTextColor(Color.parseColor("#888888"))
            }
            textCol.addView(tvName)
            textCol.addView(tvDetail)

            val btn = Button(this).apply {
                text = if (isGranted) "완료" else "설정"
                textSize = 12f
                isEnabled = !isGranted
                setOnClickListener { action() }
            }

            row.addView(statusIcon)
            row.addView(textCol)
            row.addView(btn)
            root.addView(row)

            // 구분선
            root.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#22000000"))
            })
        }

        permDialog = AlertDialog.Builder(this)
            .setTitle("권한 설정")
            .setView(root)
            .setPositiveButton("닫기", null)
            .create()
        permDialog?.show()
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

    // ─── 개발중 다이얼로그 ────────────────────────────────────────────
    private fun showInDevelopmentDialog() {
        AlertDialog.Builder(this)
            .setMessage("현재 개발중입니다.")
            .setPositiveButton("확인", null)
            .show()
    }
}

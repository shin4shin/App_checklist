package com.example.homeworktracker

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MoreFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_more, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCards(view)
        setupSyncCard(view)
    }

    override fun onResume() {
        super.onResume()
        view?.let {
            setupCards(it)
            setupSyncCard(it)
        }
    }

    private fun setupSyncCard(view: View) {
        val ctx = requireContext()
        val syncManager = SyncManager(ctx)
        val tvTitle = view.findViewById<TextView>(R.id.tvSyncAccountTitle)
        val tvStatus = view.findViewById<TextView>(R.id.tvSyncAccountStatus)
        val btnAction = view.findViewById<MaterialButton>(R.id.btnSyncAction)
        val layoutDetails = view.findViewById<View>(R.id.layoutSyncDetails)
        val tvLastSync = view.findViewById<TextView>(R.id.tvLastSyncTime)
        val btnSyncNow = view.findViewById<MaterialButton>(R.id.btnSyncNow)

        tvTitle.text = "데스크톱 위젯 실시간 연동"
        tvStatus.text = "PC 바탕화면 위젯과 6자리 코드로 연동"
        btnAction.text = "연동 코드"
        layoutDetails.visibility = View.VISIBLE

        fun updateLastSync() {
            val lastTime = syncManager.getLastSyncTime()
            if (lastTime > 0) {
                val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
                tvLastSync.text = "마지막 동기화: ${sdf.format(Date(lastTime))}"
            } else {
                tvLastSync.text = "동기화 준비 완료"
            }
        }

        updateLastSync()

        btnAction.setOnClickListener {
            btnAction.isEnabled = false
            btnAction.text = "발급 중..."
            syncManager.generatePairingCodeAsync(onSyncFailure = { err ->
                Toast.makeText(ctx, "코드는 발급됐지만 일정 동기화가 지연됐습니다: ${err.message}", Toast.LENGTH_LONG).show()
            }) { result ->
                btnAction.isEnabled = true
                btnAction.text = "연동 코드"
                result.onSuccess { code ->
                    showPairingDialog(code)
                    updateLastSync()
                }.onFailure { err ->
                    Toast.makeText(ctx, "코드 발급 실패: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnSyncNow.setOnClickListener {
            btnSyncNow.isEnabled = false
            btnSyncNow.text = "동기화 중..."
            syncManager.syncNowAsync { result ->
                btnSyncNow.isEnabled = true
                btnSyncNow.text = "지금 동기화"
                result.onSuccess {
                    Toast.makeText(ctx, "클라우드 동기화 완료!", Toast.LENGTH_SHORT).show()
                    updateLastSync()
                }.onFailure { err ->
                    Toast.makeText(ctx, "동기화 실패: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showPairingDialog(code: String) {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            gravity = Gravity.CENTER
        }

        val tvCode = TextView(ctx).apply {
            text = code
            textSize = 34f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.md_primary))
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }

        val tvDesc = TextView(ctx).apply {
            text = "PC 바탕화면 위젯에서 위 6자리 연동 코드를 입력하세요.\n(유효 시간: 10분)"
            textSize = 13f
            setTextColor(ctx.getColor(R.color.md_on_surface_variant))
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }

        layout.addView(tvCode)
        layout.addView(tvDesc)

        AlertDialog.Builder(ctx)
            .setTitle("데스크톱 위젯 연동 코드")
            .setView(layout)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun setupCards(view: View) {
        val ctx = requireContext()
        view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchDeadlineNotifications).apply {
            setOnCheckedChangeListener(null)
            isChecked = DeadlineNotifications.enabled(ctx)
            setOnCheckedChangeListener { _, checked ->
                DeadlineNotifications.setEnabled(ctx, checked)
                if (checked) (requireActivity() as HomeActivity).requestDeadlinePermission()
            }
        }
        view.findViewById<View>(R.id.btnDeadlineNotificationSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
        val overlayOk = Settings.canDrawOverlays(ctx)
        val accessibilityOk = isAccessibilityEnabled()
        val alarmOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (ctx.getSystemService(android.content.Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        else true

        applyStatus(view.findViewById(R.id.iconMoreOverlay), view.findViewById(R.id.tvMoreOverlayStatus), overlayOk)
        applyStatus(view.findViewById(R.id.iconMoreAccessibility), view.findViewById(R.id.tvMoreAccessibilityStatus), accessibilityOk)
        applyStatus(view.findViewById(R.id.iconMoreAlarm), view.findViewById(R.id.tvMoreAlarmStatus), alarmOk)

        view.findViewById<MaterialCardView>(R.id.cardMoreOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}")))
        }
        view.findViewById<MaterialCardView>(R.id.cardMoreAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.findViewById<MaterialCardView>(R.id.cardMoreAlarm).setOnClickListener {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${ctx.packageName}")))
            }
        }
        view.findViewById<MaterialCardView>(R.id.cardMorePremium).setOnClickListener {
            // 개발 중
        }
    }

    private fun applyStatus(icon: TextView, status: TextView, granted: Boolean) {
        val ctx = requireContext()
        if (granted) {
            icon.text = "✓"
            icon.setTextColor(ctx.getColor(android.R.color.holo_green_dark))
            status.text = "완료"
        } else {
            icon.text = "!"
            icon.setTextColor(ctx.getColor(android.R.color.holo_orange_dark))
            status.text = "설정 필요"
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val ctx = requireContext()
        val service = "${ctx.packageName}/${GameOverlayService::class.java.canonicalName}"
        return try {
            val enabled = Settings.Secure.getInt(ctx.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (enabled == 0) return false
            val services = Settings.Secure.getString(ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            services.split(":").any { it.equals(service, ignoreCase = true) }
        } catch (e: Exception) { false }
    }
}

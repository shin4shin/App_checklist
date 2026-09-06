package com.example.homeworktracker

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView

class MoreFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_more, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCards(view)
    }

    override fun onResume() {
        super.onResume()
        view?.let { setupCards(it) }
    }

    private fun setupCards(view: View) {
        val ctx = requireContext()
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

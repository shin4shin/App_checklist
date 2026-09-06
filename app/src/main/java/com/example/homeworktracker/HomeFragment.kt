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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val dateFormat = SimpleDateFormat("yyyy년 M월 d일 EEEE", Locale.KOREAN)
        view.findViewById<TextView>(R.id.tvTodayDate).text = dateFormat.format(Date())

        view.findViewById<View>(R.id.btnGoDaily).setOnClickListener {
            startActivity(Intent(requireContext(), MainActivity::class.java))
        }

        setupPermissionCards(view)
    }

    override fun onResume() {
        super.onResume()
        view?.let { setupPermissionCards(it) }
    }

    private fun setupPermissionCards(view: View) {
        val overlayOk = Settings.canDrawOverlays(requireContext())
        val accessibilityOk = isAccessibilityEnabled()
        val alarmOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (requireContext().getSystemService(android.content.Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        else true

        applyPermissionStatus(
            view.findViewById(R.id.iconOverlay),
            view.findViewById(R.id.tvOverlayStatus),
            overlayOk
        )
        applyPermissionStatus(
            view.findViewById(R.id.iconAccessibility),
            view.findViewById(R.id.tvAccessibilityStatus),
            accessibilityOk
        )
        applyPermissionStatus(
            view.findViewById(R.id.iconAlarm),
            view.findViewById(R.id.tvAlarmStatus),
            alarmOk
        )

        view.findViewById<MaterialCardView>(R.id.cardPermOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}")))
        }
        view.findViewById<MaterialCardView>(R.id.cardPermAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.findViewById<MaterialCardView>(R.id.cardPermAlarm).setOnClickListener {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${requireContext().packageName}")))
            }
        }
    }

    private fun applyPermissionStatus(icon: TextView, status: TextView, granted: Boolean) {
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

// ResetReceiver.kt
package com.example.homeworktracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

class ResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val targetPackage = intent.getStringExtra("target_package") ?: return
        val targetCategory = intent.getStringExtra("target_category") ?: "Daily"

        // 해당 카테고리 완료 상태 초기화
        val donePrefs = context.getSharedPreferences("done_status", Context.MODE_PRIVATE)
        donePrefs.edit { putBoolean(HomeworkWidget.doneKey(targetPackage, targetCategory), false) }
        try {
            val json = context.getSharedPreferences("app_tasks", Context.MODE_PRIVATE)
                .getString(targetPackage, "{}") ?: "{}"
            donePrefs.edit {
                if (json.trim().startsWith("[")) {
                    // 구버전 flat 배열 형식 — Daily로 취급
                    if (targetCategory == "Daily") {
                        val arr = JSONArray(json)
                        for (i in 0 until arr.length()) remove("${targetPackage}_Daily_$i")
                    }
                } else {
                    val obj = JSONObject(json)
                    if (obj.has(targetCategory)) {
                        val arr = obj.getJSONArray(targetCategory)
                        for (i in 0 until arr.length()) remove("${targetPackage}_${targetCategory}_$i")
                    }
                }
            }
        } catch (e: Exception) { }

        // 위젯 + 오버레이 갱신
        HomeworkWidget.updateAllWidgets(context)
        MiniWidget.updateAllWidgets(context)
        SmallWidget.updateAllWidgets(context)
        GameOverlayService.refreshIfShowing(targetPackage)

        // 다음 알람 재예약
        val prefs = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
        val hour = prefs.getInt("${targetPackage}_${targetCategory}_hour", -1)
        val minute = prefs.getInt("${targetPackage}_${targetCategory}_minute", 0)

        if (hour >= 0) {
            val daysStr = prefs.getString("${targetPackage}_${targetCategory}_days", "") ?: ""
            val configuredDays = if (daysStr.isEmpty()) emptySet()
            else daysStr.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

            val now = Calendar.getInstance()
            val calendar = if (configuredDays.isNotEmpty()) {
                var found: Calendar? = null
                for (offset in 0..6) {
                    val candidate = Calendar.getInstance().apply {
                        add(Calendar.DATE, offset)
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    if (candidate.timeInMillis > now.timeInMillis &&
                        candidate.get(Calendar.DAY_OF_WEEK) in configuredDays) {
                        found = candidate
                        break
                    }
                }
                found ?: Calendar.getInstance().apply {
                    add(Calendar.DATE, 7)
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            } else {
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    add(Calendar.DATE, 1)
                }
            }

            val requestCode = "${targetPackage}_${targetCategory}".hashCode()
            val newIntent = Intent(context, ResetReceiver::class.java).apply {
                putExtra("target_package", targetPackage)
                putExtra("target_category", targetCategory)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }
        }
    }
}

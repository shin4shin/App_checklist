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

class ResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val targetPackage = intent.getStringExtra("target_package") ?: return

        // 완료 상태 초기화
        context.getSharedPreferences("done_status", Context.MODE_PRIVATE)
            .edit { putBoolean(targetPackage, false) }

        // 위젯 갱신
        HomeworkWidget.updateAllWidgets(context)
        MiniWidget.updateAllWidgets(context)
        SmallWidget.updateAllWidgets(context)

        // 다음날 같은 시간에 다시 예약
        val prefs = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
        val hour = prefs.getInt("${targetPackage}_hour", -1)
        val minute = prefs.getInt("${targetPackage}_minute", 0)

        if (hour >= 0) {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DATE, 1) // 무조건 다음날
            }

            val newIntent = Intent(context, ResetReceiver::class.java).apply {
                putExtra("target_package", targetPackage)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                targetPackage.hashCode(),
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
                    // 정확한 알람 권한 없으면 일반 알람으로 폴백
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
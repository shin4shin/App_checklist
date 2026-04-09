// BootReceiver.kt
package com.example.homeworktracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val addedApps = context.getSharedPreferences("added_apps", Context.MODE_PRIVATE)
        val packages = addedApps.getStringSet("apps", emptySet()) ?: return

        val resetPrefs = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        for (pkg in packages) {
            val hour = resetPrefs.getInt("${pkg}_hour", -1)
            val minute = resetPrefs.getInt("${pkg}_minute", 0)
            if (hour < 0) continue

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                // 이미 지난 시간이면 다음날로
                if (timeInMillis <= Calendar.getInstance().timeInMillis) {
                    add(Calendar.DATE, 1)
                }
            }

            val resetIntent = Intent(context, ResetReceiver::class.java).apply {
                putExtra("target_package", pkg)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                pkg.hashCode(),
                resetIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
                )
            }
        }
    }
}

package com.example.homeworktracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.Calendar

object ResetScheduler {
    private fun intent(context: Context, pkg: String, category: String) = Intent(context, ResetReceiver::class.java).apply {
        data = Uri.Builder().scheme("checklist").authority("reset").appendPath(pkg).appendPath(category).build()
        putExtra("target_package", pkg)
        putExtra("target_category", category)
    }

    fun schedule(context: Context, pkg: String, category: String) {
        cancel(context, pkg, category)
        if (category !in TaskRepository.categories || pkg !in TaskRepository(context).packages(category)) return
        val prefs = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
        val next = if (category == "Event") {
            EventDeadline.get(context, pkg).takeIf { it > System.currentTimeMillis() } ?: return
        } else {
            val hour = prefs.getInt("${pkg}_${category}_hour", -1)
            val minute = prefs.getInt("${pkg}_${category}_minute", 0)
            if (hour !in 0..23 || minute !in 0..59) return
            val configured = prefs.getString("${pkg}_${category}_days", "").orEmpty()
                .split(",").mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }.toSet()
            val days = if (category == "Weekly") configured.ifEmpty { setOf(Calendar.MONDAY) } else emptySet()
            ResetSchedule.next(Calendar.getInstance(), hour, minute, days)
        }
        val pending = PendingIntent.getBroadcast(context, 0, intent(context, pkg, category),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            } catch (_: SecurityException) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            }
        } else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
    }

    fun cancel(context: Context, pkg: String, category: String) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        // Remove both historical request-code formats as well as the collision-free URI format.
        val candidates = listOf(
            0 to intent(context, pkg, category),
            "${pkg}_${category}".hashCode() to Intent(context, ResetReceiver::class.java),
            pkg.hashCode() to Intent(context, ResetReceiver::class.java)
        )
        for ((code, action) in candidates) {
            PendingIntent.getBroadcast(context, code, action, flags)?.let { manager.cancel(it); it.cancel() }
        }
    }

    fun restore(context: Context) {
        val repo = TaskRepository(context)
        // Cancel old alarms even for apps removed before the storage migration.
        val legacy = context.getSharedPreferences("added_apps", Context.MODE_PRIVATE).getStringSet("apps", emptySet()).orEmpty()
        val scheduled = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE).all.keys
            .filter { it.endsWith("_hour") }.map { key ->
                key.removeSuffix("_hour").let { value ->
                    TaskRepository.categories.fold(value) { result, category -> result.removeSuffix("_$category") }
                }
            }
        for (pkg in legacy + scheduled + repo.packages()) for (category in TaskRepository.categories) schedule(context, pkg, category)
    }
}

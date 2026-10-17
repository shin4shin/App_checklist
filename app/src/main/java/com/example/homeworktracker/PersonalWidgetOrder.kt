package com.example.homeworktracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

object PersonalWidgetOrder {
    const val DELAY = 5 * 60_000L
    private fun prefs(context: Context) = context.getSharedPreferences("personal_widget_order", 0)

    fun record(context: Context, id: String, done: Boolean) {
        prefs(context).edit().apply {
            if (done) putLong(id, System.currentTimeMillis()) else remove(id)
        }.apply()
    }

    fun plans(context: Context, now: Long = System.currentTimeMillis()): List<TaskItem> {
        val times = prefs(context)
        fun moved(item: TaskItem) = item.done && now >= times.getLong(item.id, 0L) + DELAY
        return PersonalPlanRepository(context).plans().sortedWith(
            compareBy<TaskItem> { moved(it) }
                .thenBy { if (moved(it)) times.getLong(it.id, 0L) else if (it.deadline > 0) it.deadline else Long.MAX_VALUE }
                .thenBy { it.title })
    }

    fun schedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 8102,
            Intent(context, PersonalWidget::class.java).setAction(PersonalWidget.ACTION_RESORT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        if (AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, PersonalWidget::class.java)).isEmpty()) return
        val now = System.currentTimeMillis()
        val times = prefs(context)
        val next = PersonalPlanRepository(context).plans().filter { it.done }
            .map { times.getLong(it.id, 0L) + DELAY }.filter { it > now }.minOrNull() ?: return
        if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
            try { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending) }
            catch (_: SecurityException) { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending) }
        } else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
    }
}

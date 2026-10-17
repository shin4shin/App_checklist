package com.example.homeworktracker

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object DeadlineNotifications {
    const val CHANNEL = "deadline_reminders"
    const val NOTIFICATION_ID = 8101
    const val LEAD = 86_400_000L
    const val OPEN = "open_deadline_personal"
    const val DISMISSED = "com.example.homeworktracker.DEADLINE_DISMISSED"
    data class Entry(val title: String, val deadline: Long, val personal: Boolean)
    fun enabled(context: Context) = context.getSharedPreferences("deadline_notifications", 0).getBoolean("enabled", true)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("deadline_notifications", 0).edit().putBoolean("enabled", enabled).apply()
        sync(context)
    }
    fun entries(context: Context): List<Entry> {
        val personal = PersonalPlanRepository(context).plans().filter { !it.done && it.deadline > 0 }
            .map { Entry(it.title, it.deadline, true) }
        val repo = TaskRepository(context)
        val events = repo.packages("Event").flatMap { pkg ->
            val name = try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }
                catch (_: PackageManager.NameNotFoundException) { pkg }
            repo.tasks(pkg, "Event").filter { !it.done && it.deadline > 0 }
                .map { Entry("$name · ${it.title}", it.deadline, false) }
        }
        return (personal + events).sortedBy { it.deadline }
    }
    fun sync(context: Context, now: Long = System.currentTimeMillis(), silent: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "마감 임박 일정", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "나의 계획과 앱별 이벤트를 마감 24시간 전부터 알려줍니다."
        })
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 8101, Intent(context, DeadlineNotificationReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        if (!enabled(context)) { manager.cancel(NOTIFICATION_ID); return }
        val entries = entries(context).filter { it.deadline > now }
        val next = entries.flatMap { listOf(it.deadline - LEAD, it.deadline) }.filter { it > now }.minOrNull()
        if (next != null) {
            if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
                try { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending) }
                catch (_: SecurityException) { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending) }
            } else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        }
        val due = entries.filter { it.deadline - now <= LEAD }
        if (due.isEmpty()) { manager.cancel(NOTIFICATION_ID); return }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val first = due.first()
        val open = Intent(context, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(OPEN, first.personal)
            if (!first.personal) putExtra("category", "Event")
        }
        val content = PendingIntent.getActivity(context, 8101, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismissed = PendingIntent.getBroadcast(context, 8101,
            Intent(context, DeadlineNotificationReceiver::class.java).setAction(DISMISSED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val style = NotificationCompat.InboxStyle().setBigContentTitle("마감이 가까운 일정 ${due.size}개")
        due.take(6).forEach { style.addLine("${it.title} · ${EventDeadline.format(it.deadline, includeYear = false)}까지") }
        if (due.size > 6) style.setSummaryText("외 ${due.size - 6}개")
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_monthly)
            .setContentTitle(if (due.size == 1) first.title else "${first.title} 외 ${due.size - 1}개")
            .setContentText("${EventDeadline.format(first.deadline, includeYear = false)}까지 · 마감 24시간 이내")
            .setStyle(style).setContentIntent(content)
            .setDeleteIntent(dismissed).setSilent(silent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true).setOnlyAlertOnce(true).setAutoCancel(false)
            .setWhen(first.deadline).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            .setTimeoutAfter(first.deadline - now)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}

class DeadlineNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DeadlineNotifications.sync(context, silent = intent.action == DeadlineNotifications.DISMISSED)
    }
}

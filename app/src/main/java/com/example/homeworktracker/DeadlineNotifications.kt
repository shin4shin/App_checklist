package com.example.homeworktracker

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object DeadlineNotifications {
    const val CHANNEL = "deadline_reminders"
    const val NOTIFICATION_ID = 8101
    const val LEAD = 86_400_000L
    const val OPEN = "open_deadline_personal"
    const val DISMISSED = "com.example.homeworktracker.DEADLINE_DISMISSED"
    data class Entry(val title: String, val deadline: Long, val personal: Boolean, val category: String = "Event")
    fun enabled(context: Context) = context.getSharedPreferences("deadline_notifications", 0).getBoolean("enabled", true)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("deadline_notifications", 0).edit().putBoolean("enabled", enabled).apply()
        sync(context)
    }
    fun entries(context: Context, now: Long = System.currentTimeMillis()): List<Entry> {
        val personal = PersonalPlanRepository(context).plans().filter { !it.done && it.deadline > 0 }
            .map { Entry(it.title, it.deadline, true) }
        val repo = TaskRepository(context)
        val events = repo.packages("Event").flatMap { pkg ->
            val name = SharedGameInfo.resolve(context, pkg)?.name ?: return@flatMap emptyList<Entry>()
            repo.tasks(pkg, "Event").filter { !it.done && it.deadline > 0 }
                .map { Entry("${if (name == pkg) "이벤트" else name} · ${it.title}", it.deadline, false) }
        }
        val weekly = repo.packages("Weekly").flatMap { pkg ->
            val name = SharedGameInfo.resolve(context, pkg)?.name ?: return@flatMap emptyList<Entry>()
            val deadline = ResetScheduler.nextResetTime(context, pkg, "Weekly", now) ?: return@flatMap emptyList<Entry>()
            repo.tasks(pkg, "Weekly").filter { !it.done }.map {
                Entry("${if (name == pkg) "위클리" else name} · ${it.title}", deadline, false, "Weekly")
            }
        }
        return (personal + events + weekly).sortedBy { it.deadline }
    }
    fun sync(context: Context, now: Long = System.currentTimeMillis(), silent: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "곧 끝나는 일정", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "일정 기한·이벤트 종료·위클리 초기화 하루 전부터 알려줍니다."
        })
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 8101, Intent(context, DeadlineNotificationReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        if (!enabled(context)) { manager.cancel(NOTIFICATION_ID); return }
        val entries = entries(context, now).filter { it.deadline > now }
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
            if (!first.personal) putExtra("category", first.category)
        }
        val content = PendingIntent.getActivity(context, 8101, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismissed = PendingIntent.getBroadcast(context, 8101,
            Intent(context, DeadlineNotificationReceiver::class.java).setAction(DISMISSED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val style = NotificationCompat.InboxStyle().setBigContentTitle("곧 끝나는 일정 ${due.size}개")
        fun timeLabel(entry: Entry) = EventDeadline.format(entry.deadline, includeYear = false) + when {
            entry.personal -> "까지"
            entry.category == "Weekly" -> " 초기화"
            else -> " 종료"
        }
        due.take(6).forEach { style.addLine("${it.title} · ${timeLabel(it)}") }
        if (due.size > 6) style.setSummaryText("외 ${due.size - 6}개")
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_monthly)
            .setContentTitle(if (due.size == 1) first.title else "${first.title} 외 ${due.size - 1}개")
            .setContentText("${timeLabel(first)} · 24시간 이내")
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

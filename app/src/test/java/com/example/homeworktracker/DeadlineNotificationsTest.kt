package com.example.homeworktracker

import android.app.NotificationManager
import android.app.Notification
import android.app.AlarmManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeadlineNotificationsTest {
    @Test fun dismissedNotificationReturnsSilentlyAndRechecksCurrentPlans() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val repo = PersonalPlanRepository(context)
        val plan = TaskItem(title = "제출", deadline = System.currentTimeMillis() + 3_600_000L)
        repo.save(plan)
        val manager = context.getSystemService(NotificationManager::class.java)
        val original = shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID)
        val dismissed = shadowOf(original.deleteIntent).savedIntent
        assertEquals(DeadlineNotifications.DISMISSED, dismissed.action)
        val receiver = DeadlineNotificationReceiver()
        repeat(2) {
            manager.cancel(DeadlineNotifications.NOTIFICATION_ID)
            receiver.onReceive(context, dismissed)
            val restored = shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID)
            assertNotNull(restored)
            assertNull(restored.sound)
            assertNull(restored.vibrate)
            assertEquals(0, restored.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
            assertEquals(Notification.GROUP_ALERT_SUMMARY, restored.groupAlertBehavior)
            assertTrue(restored.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertNotNull(restored.deleteIntent)
        }
        repo.setDone(plan.id, true)
        receiver.onReceive(context, dismissed)
        assertNull(shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID))
        repo.setDone(plan.id, false)
        repo.save(plan.copy(deadline = System.currentTimeMillis() - 1_000L))
        receiver.onReceive(context, dismissed)
        assertNull(shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID))
        repo.save(plan)
        DeadlineNotifications.setEnabled(context, false)
        receiver.onReceive(context, dismissed)
        assertNull(shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID))
    }

    @Test fun alertsAt24HoursAndRemovesOnCompletionAndDeadline() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val repo = PersonalPlanRepository(context)
        val now = System.currentTimeMillis()
        val plan = TaskItem(title = "제출", deadline = now + DeadlineNotifications.LEAD + 10_000L)
        repo.save(plan)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNull(shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID))
        DeadlineNotifications.sync(context, now + 10_000L)
        val notification = shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID)
        assertNotNull(notification)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        repo.setDone(plan.id, true)
        assertNull(shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID))
        repo.setDone(plan.id, false)
        DeadlineNotifications.sync(context, plan.deadline)
        assertNull(shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID))
    }
    @Test fun combinesBothModesAndReschedulesEditsAndDisable() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = "game"
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "game"; nonLocalizedLabel = "게임" }
        })
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val now = System.currentTimeMillis()
        val personal = PersonalPlanRepository(context)
        val plan = TaskItem(title = "읽기", deadline = now + 3_600_000L)
        personal.save(plan)
        val apps = TaskRepository(context)
        apps.addApp("game", "Event")
        apps.saveTasks("game", "Event", listOf(TaskItem(title = "게임 이벤트", deadline = now + 7_200_000L)))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID).extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("외 1개"))
        personal.save(plan.copy(deadline = now + 3 * DeadlineNotifications.LEAD))
        apps.setDone("game", "Event", true)
        assertNull(shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID))
        personal.save(plan)
        assertNotNull(shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID))
        DeadlineNotifications.setEnabled(context, false)
        assertNull(shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID))
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.none {
            shadowOf(it.operation).savedIntent.component?.className == DeadlineNotificationReceiver::class.java.name
        })
    }
    @Test fun ignoresUninstalledPackageAliasInNotificationCountAndText() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val pkg = "com.HoYoverse.Nap"
        shadowOf(context.packageManager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = pkg
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = "젠레스 존 제로" }
        })
        val repo = TaskRepository(context)
        for (name in listOf("com.hoyoverse.nap", pkg)) {
            repo.addApp(name, "Event")
            repo.saveTasks(name, "Event", listOf(TaskItem(title = "사이버 명의, 출동!", deadline = System.currentTimeMillis() + 3_600_000L)))
        }
        val entries = DeadlineNotifications.entries(context)
        assertEquals(1, entries.size)
        assertEquals("젠레스 존 제로 · 사이버 명의, 출동!", entries.single().title)
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(DeadlineNotifications.NOTIFICATION_ID)
        assertFalse(notification.extras.toString().contains("com.hoyoverse.nap"))
    }

    @Test fun weeklyReminderUsesNextResetAndDisappearsWhenCompleted() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val pkg = context.packageName
        val now = java.util.Calendar.getInstance().apply { clear(); set(2026, 10, 15, 12, 0, 0) }.timeInMillis
        val deadline = java.util.Calendar.getInstance().apply { clear(); set(2026, 10, 16, 4, 30, 0) }.timeInMillis
        context.getSharedPreferences("reset_times", 0).edit().putInt("${pkg}_Weekly_hour", 4)
            .putInt("${pkg}_Weekly_minute", 30).putString("${pkg}_Weekly_days", "2").commit()
        val repo = TaskRepository(context)
        repo.addApp(pkg, "Weekly")
        val item = TaskItem(title = "주간 보상 수령")
        val completed = TaskItem(title = "완료")
        repo.saveTasks(pkg, "Weekly", listOf(item, completed))
        repo.setTaskDone(pkg, "Weekly", completed.id, true)
        val manager = context.getSystemService(NotificationManager::class.java)
        DeadlineNotifications.sync(context, deadline - DeadlineNotifications.LEAD - 1)
        assertNull(shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID))
        DeadlineNotifications.sync(context, now)
        val notification = shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID)
        assertNotNull(notification)
        assertEquals("곧 끝나는 일정 1개", notification.extras.getCharSequence(Notification.EXTRA_TITLE_BIG).toString())
        assertEquals("Weekly", shadowOf(notification.contentIntent).savedIntent.getStringExtra("category"))
        assertEquals(deadline, DeadlineNotifications.entries(context, now).single().deadline)
        repo.setTaskDone(pkg, "Weekly", item.id, true)
        DeadlineNotifications.sync(context, now)
        assertNull(shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID))
    }

    @Test fun deniedPermissionDoesNotCrashOrPostNotification() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        PersonalPlanRepository(context).save(TaskItem(title = "권한 없음", deadline = System.currentTimeMillis() + 3_600_000L))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNull(shadowOf(manager).getNotification(DeadlineNotifications.NOTIFICATION_ID))
    }
}

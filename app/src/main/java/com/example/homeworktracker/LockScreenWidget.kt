package com.example.homeworktracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

class LockScreenWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id)
    }
    companion object {
        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, LockScreenWidget::class.java)).forEach { update(context, manager, it) }
        }
        internal fun progress(context: Context, category: String): String {
            val repo = TaskRepository(context)
            val tasks = repo.packages(category).filter { SharedGameInfo.resolve(context, it) != null }.flatMap { pkg ->
                repo.tasks(pkg, category).ifEmpty {
                    if (category == "Daily") listOf(TaskItem(title = "", done = repo.isDone(pkg, category))) else emptyList()
                }
            }
            return "${if (category == "Daily") "데일리" else "위클리"} ${tasks.count { it.done }}/${tasks.size}"
        }
        internal fun openIntent(context: Context, entry: DeadlineNotifications.Entry? = null): Intent = Intent(context, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(DeadlineNotifications.OPEN, entry?.personal ?: false)
            putExtra("category", if (entry?.personal == true) "Personal" else entry?.category ?: "Home")
        }
        internal fun dueEntries(context: Context, now: Long = System.currentTimeMillis()) =
            DeadlineNotifications.entries(context, now).filter { it.deadline - now in 1..DeadlineNotifications.LEAD }.take(2)

        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val now = System.currentTimeMillis()
            val entries = dueEntries(context, now)
            val views = RemoteViews(context.packageName, R.layout.widget_lock_screen)
            val home = PendingIntent.getActivity(context, id * 3, openIntent(context).apply { data = android.net.Uri.parse("checklist://lock-widget/$id/home") }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.lockWidgetRoot, home)
            views.setTextViewText(R.id.lockWidgetProgress, "${progress(context, "Daily")} · ${progress(context, "Weekly")}")
            val ids = listOf(Triple(R.id.lockWidgetFirst, R.id.lockWidgetFirstTitle, R.id.lockWidgetFirstTime), Triple(R.id.lockWidgetSecond, R.id.lockWidgetSecondTitle, R.id.lockWidgetSecondTime))
            val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160)
            val compact = height < 132
            views.setViewVisibility(R.id.lockWidgetProgress, if (height < 112) View.GONE else View.VISIBLE)
            ids.forEachIndexed { index, (row, title, time) ->
                val entry = entries.getOrNull(index)
                views.setViewVisibility(row, if (index == 1 && (entry == null)) View.GONE else View.VISIBLE)
                views.setTextViewText(title, entry?.title ?: "24시간 이내 일정 없음")
                views.setViewVisibility(time, View.VISIBLE)
                views.setTextViewText(time, entry?.let {
                    val remaining = EventDeadline.remaining(it.deadline, now)
                    if (compact) remaining else "$remaining · ${EventDeadline.format(it.deadline, includeYear = false)} ${if (it.personal) "까지" else if (it.category == "Weekly") "초기화" else "종료"}"
                } ?: "일정을 눌러 확인")
                val pending = PendingIntent.getActivity(context, id * 3 + index + 1, openIntent(context, entry).apply { data = android.net.Uri.parse("checklist://lock-widget/$id/$index") }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setInt(time, "setMaxLines", if (compact) 1 else 2)
                views.setOnClickPendingIntent(row, pending)
                views.setContentDescription(row, if (entry == null) "24시간 이내 일정 없음. 일정 열기" else "${entry.title}, ${EventDeadline.format(entry.deadline, false)}")
            }
            manager.updateAppWidget(id, views)
        }
    }
}

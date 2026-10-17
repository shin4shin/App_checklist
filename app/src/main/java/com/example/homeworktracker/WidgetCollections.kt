package com.example.homeworktracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

object WidgetCollections {
    const val TASK_ID = "task_id"
    private const val OPERATION = "operation"
    data class Row(val key: String, val title: String, val detail: String, val done: Boolean,
                   val pkg: String? = null, val taskId: String? = null, val category: String = "Daily", val deadline: Long = 0L) {
        val stableId: Long get() = key.fold(1125899906842597L) { hash, char -> hash * 31 + char.code }
    }

    fun rows(context: Context, kind: String, category: String): List<Row> {
        if (kind == "personal") return PersonalWidgetOrder.plans(context).map {
            Row(it.id, it.title, EventDeadline.label(it.deadline), it.done, taskId = it.id)
        }
        val repo = TaskRepository(context)
        val times = context.getSharedPreferences("reset_times", 0)
        val packages = repo.packages(category).filter {
            category != "Weekly" || (times.getInt("${it}_Weekly_hour", -1) in 0..23 &&
                times.getInt("${it}_Weekly_minute", 0) in 0..59)
        }.mapNotNull { pkg ->
            try { pkg to context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
        }.let { apps ->
            val sortMode = context.getSharedPreferences("sort_prefs", 0).getInt("sort_mode", 0)
            if (kind != "homework" || sortMode == 0) apps.sortedBy { it.second }
            else apps.sortedBy { (pkg, _) ->
                if (category == "Event") EventDeadline.get(context, pkg).takeIf { it > 0 } ?: Long.MAX_VALUE
                else times.getInt("${pkg}_${category}_hour", -1).let { hour ->
                    if (hour < 0) Long.MAX_VALUE else (hour * 60 + times.getInt("${pkg}_${category}_minute", 0)).toLong()
                }
            }
        }
        return packages.flatMap { (pkg, name) ->
            if (category == "Daily") listOf(Row(pkg, name, "", repo.isDone(pkg, category), pkg))
            else repo.tasks(pkg, category).map { task ->
                Row("$pkg/$category/${task.id}", task.title,
                    if (category == "Event") {
                        if (task.deadline > 0) EventDeadline.remaining(task.deadline) else "마감 미설정"
                    } else "",
                    task.done, pkg, task.id, category, task.deadline)
            }
        }.let { rows ->
            if (category != "Event") rows else {
                val now = System.currentTimeMillis()
                rows.sortedWith(compareBy<Row> {
                    when { it.deadline > now -> 0; it.deadline > 0 -> 1; else -> 2 }
                }.thenBy { it.deadline })
            }
        }
    }

    fun rowView(context: Context, row: Row, kind: String): RemoteViews {
        val mini = kind == "mini"
        val views = RemoteViews(context.packageName, if (mini) R.layout.widget_collection_cell else R.layout.widget_collection_row)
        val toggle = Intent().putExtra(HomeworkWidget.EXTRA_PACKAGE, row.pkg)
            .putExtra(HomeworkWidget.EXTRA_CATEGORY, row.category).putExtra(TASK_ID, row.taskId)
            .putExtra(PersonalWidget.EXTRA_PLAN_ID, row.taskId)
        val launch = Intent(toggle).putExtra(OPERATION, "launch")
        views.setOnClickFillInIntent(R.id.widgetCheck, toggle)
        views.setOnClickFillInIntent(R.id.widgetRow, if (row.pkg == null || mini) toggle else launch)
        views.setContentDescription(R.id.widgetRow, "${row.title}, ${if (row.done) "완료" else "미완료"}, ${row.detail}")
        views.setTextViewText(R.id.widgetCheck, if (row.done) "✓" else if (mini) "" else "○")
        views.setTextColor(R.id.widgetCheck, Color.parseColor(if (row.done) "#3FB950" else "#8B949E"))
        if (row.pkg != null) {
            val icon = try { context.packageManager.getApplicationIcon(row.pkg) }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { context.packageManager.defaultActivityIcon }
            val size = (32 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, size, size); icon.draw(Canvas(bitmap))
            views.setImageViewBitmap(R.id.widgetIcon, bitmap)
            if (mini) views.setInt(R.id.widgetIcon, "setImageAlpha", if (row.done) 80 else 255)
        } else views.setViewVisibility(R.id.widgetIcon, View.GONE)
        if (!mini) {
            views.setTextViewText(R.id.widgetName, row.title)
            views.setTextViewTextSize(R.id.widgetName, android.util.TypedValue.COMPLEX_UNIT_SP,
                if (row.pkg != null) 20f else 14f)
            views.setTextColor(R.id.widgetName, Color.parseColor(if (row.done) "#3FB950" else "#F0F6FC"))
            views.setTextViewText(R.id.widgetDetail, row.detail)
            val event = row.pkg != null && row.category == "Event"
            views.setViewVisibility(R.id.widgetDetail, if (row.detail.isEmpty() || event) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.widgetRemaining, row.detail)
            views.setViewVisibility(R.id.widgetRemaining, if (event) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetLaunch, if (row.pkg == null) View.GONE else View.VISIBLE)
            views.setOnClickFillInIntent(R.id.widgetLaunch, launch)
            views.setContentDescription(R.id.widgetCheck, "${row.title} 완료 전환")
        }
        return views
    }

    fun bind(context: Context, manager: AppWidgetManager, id: Int, views: RemoteViews,
             kind: String, category: String, receiver: Class<*>, action: String, emptyId: Int) {
        val rows = rows(context, kind, category)
        views.setEmptyView(R.id.widgetItems, emptyId)
        val template = Intent(context, receiver).setAction(action).setData(Uri.parse("checklist://widget/$kind/$id"))
            .putExtra(HomeworkWidget.EXTRA_WIDGET_ID, id)
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        views.setPendingIntentTemplate(R.id.widgetItems, PendingIntent.getBroadcast(context, id, template,
            PendingIntent.FLAG_UPDATE_CURRENT or mutable))
        if (Build.VERSION.SDK_INT >= 31) {
            val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
            rows.forEach { items.addItem(it.stableId, rowView(context, it, kind)) }
            views.setRemoteAdapter(R.id.widgetItems, items.build())
        } else {
            val adapter = Intent(context, WidgetListService::class.java)
                .putExtra("kind", kind).putExtra("category", category)
                .setData(Uri.parse("checklist://list/$kind/$id/$category"))
            views.setRemoteAdapter(R.id.widgetItems, adapter)
        }
        manager.updateAppWidget(id, views)
        if (Build.VERSION.SDK_INT < 31) manager.notifyAppWidgetViewDataChanged(id, R.id.widgetItems)
    }

    fun handleAppClick(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(HomeworkWidget.EXTRA_PACKAGE) ?: return
        if (intent.getStringExtra(OPERATION) == "launch") {
            context.packageManager.getLaunchIntentForPackage(pkg)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        val category = intent.getStringExtra(HomeworkWidget.EXTRA_CATEGORY) ?: "Daily"
        val repo = TaskRepository(context)
        val taskId = intent.getStringExtra(TASK_ID)
        if (taskId != null) {
            val task = repo.tasks(pkg, category).firstOrNull { it.id == taskId } ?: return
            repo.setTaskDone(pkg, category, taskId, !task.done)
        } else repo.setDone(pkg, category, !repo.isDone(pkg, category))
    }
}

// Android 8–11 use the service-backed collection API.
class WidgetListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private val kind = intent.getStringExtra("kind") ?: "homework"
        private val category = intent.getStringExtra("category") ?: "Daily"
        private var rows = emptyList<WidgetCollections.Row>()
        override fun onCreate() { onDataSetChanged() }
        override fun onDataSetChanged() { rows = WidgetCollections.rows(this@WidgetListService, kind, category) }
        override fun onDestroy() { rows = emptyList() }
        override fun getCount() = rows.size
        override fun getViewAt(position: Int) = rows.getOrNull(position)?.let { WidgetCollections.rowView(this@WidgetListService, it, kind) }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = rows.getOrNull(position)?.stableId ?: 0L
        override fun hasStableIds() = true
    }
}

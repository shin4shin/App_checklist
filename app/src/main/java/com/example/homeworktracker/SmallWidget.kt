// SmallWidget.kt
package com.example.homeworktracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.widget.RemoteViews

class SmallWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    companion object {
        const val ACTION_TOGGLE_DONE = "com.example.homeworktracker.SMALL_TOGGLE_DONE"
        const val ACTION_NEXT_PAGE = "com.example.homeworktracker.SMALL_NEXT_PAGE"
        const val ACTION_PREV_PAGE = "com.example.homeworktracker.SMALL_PREV_PAGE"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_WIDGET_ID = "extra_widget_id"
        const val PAGE_SIZE = 9

        private val pageMap = mutableMapOf<Int, Int>()

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SmallWidget::class.java))
            for (id in ids) updateWidget(context, manager, id)
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_small)

            val prefs = context.getSharedPreferences("added_apps", Context.MODE_PRIVATE)
            val packages = prefs.getStringSet("apps", emptySet()) ?: emptySet()
            val donePrefs = context.getSharedPreferences("done_status", Context.MODE_PRIVATE)
            val pm = context.packageManager

            val appInfos = packages.mapNotNull { pkg ->
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    Triple(pm.getApplicationLabel(info).toString(), pkg, pm.getApplicationIcon(info))
                } catch (e: Exception) { null }
            }.sortedBy { it.first }

            val totalPages = if (appInfos.isEmpty()) 1 else (appInfos.size + PAGE_SIZE - 1) / PAGE_SIZE
            val currentPage = (pageMap[appWidgetId] ?: 0).coerceIn(0, totalPages - 1)
            pageMap[appWidgetId] = currentPage
            val startIndex = currentPage * PAGE_SIZE

            views.setTextViewText(R.id.tvSmallPageInfo, "${currentPage + 1}/$totalPages")

            // 타이틀 클릭 → 앱 실행
            views.setOnClickPendingIntent(R.id.tvSmallTitle,
                PendingIntent.getActivity(context, appWidgetId,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            // 페이지 버튼
            views.setOnClickPendingIntent(R.id.btnSmallNext,
                PendingIntent.getBroadcast(context, appWidgetId * 10 + 1,
                    Intent(context, SmallWidget::class.java).apply {
                        action = ACTION_NEXT_PAGE
                        putExtra(EXTRA_WIDGET_ID, appWidgetId)
                    }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            views.setOnClickPendingIntent(R.id.btnSmallPrev,
                PendingIntent.getBroadcast(context, appWidgetId * 10 + 2,
                    Intent(context, SmallWidget::class.java).apply {
                        action = ACTION_PREV_PAGE
                        putExtra(EXTRA_WIDGET_ID, appWidgetId)
                    }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val rowIds = listOf(
                Triple(R.id.smallRow1, Triple(R.id.smallCheck1, R.id.smallIcon1, R.id.smallName1), 0),
                Triple(R.id.smallRow2, Triple(R.id.smallCheck2, R.id.smallIcon2, R.id.smallName2), 1),
                Triple(R.id.smallRow3, Triple(R.id.smallCheck3, R.id.smallIcon3, R.id.smallName3), 2),
                Triple(R.id.smallRow4, Triple(R.id.smallCheck4, R.id.smallIcon4, R.id.smallName4), 3),
                Triple(R.id.smallRow5, Triple(R.id.smallCheck5, R.id.smallIcon5, R.id.smallName5), 4),
                Triple(R.id.smallRow6, Triple(R.id.smallCheck6, R.id.smallIcon6, R.id.smallName6), 5),
                Triple(R.id.smallRow7, Triple(R.id.smallCheck7, R.id.smallIcon7, R.id.smallName7), 6),
                Triple(R.id.smallRow8, Triple(R.id.smallCheck8, R.id.smallIcon8, R.id.smallName8), 7),
                Triple(R.id.smallRow9, Triple(R.id.smallCheck9, R.id.smallIcon9, R.id.smallName9), 8),
            )

            rowIds.forEach { (rowId, ids, rowIndex) ->
                val (checkId, iconId, nameId) = ids
                val appIndex = startIndex + rowIndex

                if (appIndex < appInfos.size) {
                    val (name, pkg, icon) = appInfos[appIndex]
                    val isDone = donePrefs.getBoolean(pkg, false)

                    views.setViewVisibility(rowId, android.view.View.VISIBLE)
                    views.setTextViewText(nameId, name)
                    views.setTextViewText(checkId, if (isDone) "✓" else "○")
                    views.setTextColor(checkId,
                        if (isDone) android.graphics.Color.parseColor("#4CAF50")
                        else android.graphics.Color.parseColor("#888888"))
                    views.setTextColor(nameId,
                        if (isDone) android.graphics.Color.parseColor("#4CAF50")
                        else android.graphics.Color.WHITE)

                    try { views.setImageViewBitmap(iconId, drawableToBitmap(icon)) } catch (e: Exception) { }

                    val toggleIntent = Intent(context, SmallWidget::class.java).apply {
                        action = ACTION_TOGGLE_DONE
                        putExtra(EXTRA_PACKAGE, pkg)
                        putExtra(EXTRA_WIDGET_ID, appWidgetId)
                    }
                    val togglePending = PendingIntent.getBroadcast(
                        context, appWidgetId * 100 + rowIndex, toggleIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    views.setOnClickPendingIntent(rowId, togglePending)
                    views.setOnClickPendingIntent(checkId, togglePending)
                } else {
                    views.setViewVisibility(rowId, android.view.View.GONE)
                }
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun drawableToBitmap(drawable: Drawable): Bitmap {
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.takeIf { it > 0 } ?: 64,
                drawable.intrinsicHeight.takeIf { it > 0 } ?: 64,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val manager = AppWidgetManager.getInstance(context)

        when (intent.action) {
            ACTION_TOGGLE_DONE -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
                val donePrefs = context.getSharedPreferences("done_status", Context.MODE_PRIVATE)
                donePrefs.edit().putBoolean(pkg, !donePrefs.getBoolean(pkg, false)).apply()
                updateAllWidgets(context)
                HomeworkWidget.updateAllWidgets(context)
                MiniWidget.updateAllWidgets(context)
            }
            ACTION_NEXT_PAGE -> {
                val id = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                if (id != -1) {
                    pageMap[id] = (pageMap[id] ?: 0) + 1
                    updateWidget(context, manager, id)
                }
            }
            ACTION_PREV_PAGE -> {
                val id = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                if (id != -1) {
                    pageMap[id] = maxOf(0, (pageMap[id] ?: 0) - 1)
                    updateWidget(context, manager, id)
                }
            }
        }
    }
}
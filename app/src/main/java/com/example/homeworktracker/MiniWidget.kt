// MiniWidget.kt
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

class MiniWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    companion object {
        const val ACTION_TOGGLE_DONE = "com.example.homeworktracker.MINI_TOGGLE_DONE"
        const val ACTION_NEXT_PAGE = "com.example.homeworktracker.MINI_NEXT_PAGE"
        const val ACTION_PREV_PAGE = "com.example.homeworktracker.MINI_PREV_PAGE"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_WIDGET_ID = "extra_widget_id"
        const val PAGE_SIZE = 4

        private val pageMap = mutableMapOf<Int, Int>()

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MiniWidget::class.java))
            for (id in ids) updateWidget(context, manager, id)
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_mini)

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

            // 타이틀 클릭 → 앱 실행
            views.setOnClickPendingIntent(
                R.id.tvMiniTitle,
                PendingIntent.getActivity(
                    context, appWidgetId,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            // 다음 페이지 버튼
            views.setOnClickPendingIntent(
                R.id.btnMiniNext,
                PendingIntent.getBroadcast(
                    context, appWidgetId * 10 + 1,
                    Intent(context, MiniWidget::class.java).apply {
                        action = ACTION_NEXT_PAGE
                        putExtra(EXTRA_WIDGET_ID, appWidgetId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            // 이전 페이지 버튼
            views.setOnClickPendingIntent(
                R.id.btnMiniPrev,
                PendingIntent.getBroadcast(
                    context, appWidgetId * 10 + 2,
                    Intent(context, MiniWidget::class.java).apply {
                        action = ACTION_PREV_PAGE
                        putExtra(EXTRA_WIDGET_ID, appWidgetId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            // 셀 ID 목록 (cellId, iconId, checkId)
            val cellId0 = R.id.miniCell1
            val iconId0 = R.id.miniIcon1
            val checkId0 = R.id.miniCheck1

            val cellId1 = R.id.miniCell2
            val iconId1 = R.id.miniIcon2
            val checkId1 = R.id.miniCheck2

            val cellId2 = R.id.miniCell3
            val iconId2 = R.id.miniIcon3
            val checkId2 = R.id.miniCheck3

            val cellId3 = R.id.miniCell4
            val iconId3 = R.id.miniIcon4
            val checkId3 = R.id.miniCheck4

            val cellIds = intArrayOf(cellId0, cellId1, cellId2, cellId3)
            val iconIds = intArrayOf(iconId0, iconId1, iconId2, iconId3)
            val checkIds = intArrayOf(checkId0, checkId1, checkId2, checkId3)

            for (index in 0..3) {
                val appIndex = startIndex + index
                if (appIndex < appInfos.size) {
                    val (_, pkg, icon) = appInfos[appIndex]
                    val isDone = donePrefs.getBoolean(pkg, false)

                    views.setViewVisibility(cellIds[index], android.view.View.VISIBLE)
                    try { views.setImageViewBitmap(iconIds[index], drawableToBitmap(icon)) } catch (e: Exception) { }
                    views.setInt(iconIds[index], "setAlpha", if (isDone) 80 else 255)
                    views.setViewVisibility(checkIds[index],
                        if (isDone) android.view.View.VISIBLE else android.view.View.GONE)

                    val toggleIntent = Intent(context, MiniWidget::class.java).apply {
                        action = ACTION_TOGGLE_DONE
                        putExtra(EXTRA_PACKAGE, pkg)
                        putExtra(EXTRA_WIDGET_ID, appWidgetId)
                    }
                    val togglePending = PendingIntent.getBroadcast(
                        context, appWidgetId * 100 + index, toggleIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(cellIds[index], togglePending)
                } else {
                    views.setViewVisibility(cellIds[index], android.view.View.INVISIBLE)
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
                SmallWidget.updateAllWidgets(context)
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
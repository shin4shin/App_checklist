// HomeworkWidget.kt
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

class HomeworkWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        const val ACTION_TOGGLE_DONE = "com.example.homeworktracker.TOGGLE_DONE"
        const val ACTION_NEXT_PAGE = "com.example.homeworktracker.NEXT_PAGE"
        const val ACTION_PREV_PAGE = "com.example.homeworktracker.PREV_PAGE"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_WIDGET_ID = "extra_widget_id"
        const val PAGE_SIZE = 9

        // 위젯별 현재 페이지 저장
        private val pageMap = mutableMapOf<Int, Int>()

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HomeworkWidget::class.java))
            for (id in ids) updateWidget(context, manager, id)
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_homework)

            // 앱 목록 불러오기
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

            // 페이지 계산
            val totalPages = if (appInfos.isEmpty()) 1 else (appInfos.size + PAGE_SIZE - 1) / PAGE_SIZE
            val currentPage = (pageMap[appWidgetId] ?: 0).coerceIn(0, totalPages - 1)
            pageMap[appWidgetId] = currentPage
            val startIndex = currentPage * PAGE_SIZE

            // 페이지 정보 표시
            views.setTextViewText(R.id.tvPageInfo, "${currentPage + 1}/$totalPages")

            // 타이틀 클릭 → 앱 실행
            val launchPending = PendingIntent.getActivity(
                context, appWidgetId,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.tvWidgetTitle, launchPending)

            // 다음 페이지 버튼
            val nextIntent = Intent(context, HomeworkWidget::class.java).apply {
                action = ACTION_NEXT_PAGE
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            views.setOnClickPendingIntent(R.id.btnNextPage,
                PendingIntent.getBroadcast(context, appWidgetId * 10 + 1, nextIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            // 이전 페이지 버튼
            val prevIntent = Intent(context, HomeworkWidget::class.java).apply {
                action = ACTION_PREV_PAGE
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            views.setOnClickPendingIntent(R.id.btnPrevPage,
                PendingIntent.getBroadcast(context, appWidgetId * 10 + 2, prevIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            // 행 ID 목록 (rowId, checkId, iconId, nameId, launchBtnId)
            val rowIds = listOf(
                listOf(R.id.row1, R.id.tvCheck1, R.id.ivIcon1, R.id.tvAppName1, R.id.btnLaunch1),
                listOf(R.id.row2, R.id.tvCheck2, R.id.ivIcon2, R.id.tvAppName2, R.id.btnLaunch2),
                listOf(R.id.row3, R.id.tvCheck3, R.id.ivIcon3, R.id.tvAppName3, R.id.btnLaunch3),
                listOf(R.id.row4, R.id.tvCheck4, R.id.ivIcon4, R.id.tvAppName4, R.id.btnLaunch4),
                listOf(R.id.row5, R.id.tvCheck5, R.id.ivIcon5, R.id.tvAppName5, R.id.btnLaunch5),
                listOf(R.id.row6, R.id.tvCheck6, R.id.ivIcon6, R.id.tvAppName6, R.id.btnLaunch6),
                listOf(R.id.row7, R.id.tvCheck7, R.id.ivIcon7, R.id.tvAppName7, R.id.btnLaunch7),
                listOf(R.id.row8, R.id.tvCheck8, R.id.ivIcon8, R.id.tvAppName8, R.id.btnLaunch8),
                listOf(R.id.row9, R.id.tvCheck9, R.id.ivIcon9, R.id.tvAppName9, R.id.btnLaunch9),
            )

            rowIds.forEachIndexed { rowIndex, ids ->
                val (rowId, checkId, iconId, nameId, launchBtnId) = ids.map { it }
                val appIndex = startIndex + rowIndex

                if (appIndex < appInfos.size) {
                    val (name, pkg, icon) = appInfos[appIndex]
                    val isDone = donePrefs.getBoolean(pkg, false)

                    views.setViewVisibility(rowId, android.view.View.VISIBLE)
                    views.setTextViewText(nameId, name)
                    views.setTextViewText(checkId, if (isDone) "✓" else "○")
                    views.setTextColor(checkId,
                        if (isDone) android.graphics.Color.parseColor("#4CAF50")
                        else android.graphics.Color.parseColor("#888888")
                    )
                    views.setTextColor(nameId,
                        if (isDone) android.graphics.Color.parseColor("#4CAF50")
                        else android.graphics.Color.WHITE
                    )

                    try { views.setImageViewBitmap(iconId, drawableToBitmap(icon)) } catch (e: Exception) { }

                    // 체크 토글 (행 전체 + 체크 버튼)
                    val requestCode = appWidgetId * 100 + rowIndex
                    val toggleIntent = Intent(context, HomeworkWidget::class.java).apply {
                        action = ACTION_TOGGLE_DONE
                        putExtra(EXTRA_PACKAGE, pkg)
                        putExtra(EXTRA_WIDGET_ID, appWidgetId)
                    }
                    val togglePending = PendingIntent.getBroadcast(
                        context, requestCode, toggleIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(rowId, togglePending)
                    views.setOnClickPendingIntent(checkId, togglePending)

                    // 앱 실행 버튼 (오른쪽 끝 ›)
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (launchIntent != null) {
                        val launchRequestCode = appWidgetId * 100 + rowIndex + 1000
                        val launchAppPending = PendingIntent.getActivity(
                            context, launchRequestCode, launchIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        views.setOnClickPendingIntent(launchBtnId, launchAppPending)
                        views.setViewVisibility(launchBtnId, android.view.View.VISIBLE)
                    } else {
                        views.setViewVisibility(launchBtnId, android.view.View.INVISIBLE)
                    }
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

        when (intent.action) {
            ACTION_TOGGLE_DONE -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
                val donePrefs = context.getSharedPreferences("done_status", Context.MODE_PRIVATE)
                donePrefs.edit().putBoolean(pkg, !donePrefs.getBoolean(pkg, false)).apply()
                updateAllWidgets(context)
                MiniWidget.updateAllWidgets(context)
                SmallWidget.updateAllWidgets(context)
            }
            ACTION_NEXT_PAGE -> {
                val id = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                if (id != -1) {
                    val current = pageMap[id] ?: 0
                    pageMap[id] = current + 1
                    val manager = AppWidgetManager.getInstance(context)
                    updateWidget(context, manager, id)
                }
            }
            ACTION_PREV_PAGE -> {
                val id = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                if (id != -1) {
                    val current = pageMap[id] ?: 0
                    pageMap[id] = maxOf(0, current - 1)
                    val manager = AppWidgetManager.getInstance(context)
                    updateWidget(context, manager, id)
                }
            }
        }
    }
}
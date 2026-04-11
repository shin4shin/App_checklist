// HomeworkWidget.kt
package com.example.homeworktracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.RemoteViews
import java.util.Calendar

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

        private val pageMap = mutableMapOf<Int, Int>()

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HomeworkWidget::class.java))
            for (id in ids) updateWidget(context, manager, id)
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_homework)

            val prefs = context.getSharedPreferences("added_apps", Context.MODE_PRIVATE)
            val packages = prefs.getStringSet("apps", emptySet()) ?: emptySet()
            val donePrefs = context.getSharedPreferences("done_status", Context.MODE_PRIVATE)
            val pm = context.packageManager

            val sortMode = context.getSharedPreferences("sort_prefs", Context.MODE_PRIVATE)
                .getInt("sort_mode", 0)
            val resetPrefs = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)

            val appInfos = packages.mapNotNull { pkg ->
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    Triple(pm.getApplicationLabel(info).toString(), pkg, pm.getApplicationIcon(info))
                } catch (e: Exception) { null }
            }.let { list ->
                if (sortMode == 0) list.sortedBy { it.first }
                else list.sortedWith(Comparator { a, b ->
                    val ah = resetPrefs.getInt("${a.second}_hour", -1)
                    val am = resetPrefs.getInt("${a.second}_minute", 0)
                    val bh = resetPrefs.getInt("${b.second}_hour", -1)
                    val bm = resetPrefs.getInt("${b.second}_minute", 0)
                    val aMin = if (ah >= 0) ah * 60 + am else Int.MAX_VALUE
                    val bMin = if (bh >= 0) bh * 60 + bm else Int.MAX_VALUE
                    aMin - bMin
                })
            }

            val totalPages = if (appInfos.isEmpty()) 1 else (appInfos.size + PAGE_SIZE - 1) / PAGE_SIZE
            val currentPage = (pageMap[appWidgetId] ?: 0).coerceIn(0, totalPages - 1)
            pageMap[appWidgetId] = currentPage
            val startIndex = currentPage * PAGE_SIZE

            views.setTextViewText(R.id.tvPageInfo, "${currentPage + 1}/$totalPages")

            val launchPending = PendingIntent.getActivity(
                context, appWidgetId,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.tvWidgetTitle, launchPending)

            val nextIntent = Intent(context, HomeworkWidget::class.java).apply {
                action = ACTION_NEXT_PAGE
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            views.setOnClickPendingIntent(R.id.btnNextPage,
                PendingIntent.getBroadcast(context, appWidgetId * 10 + 1, nextIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val prevIntent = Intent(context, HomeworkWidget::class.java).apply {
                action = ACTION_PREV_PAGE
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            views.setOnClickPendingIntent(R.id.btnPrevPage,
                PendingIntent.getBroadcast(context, appWidgetId * 10 + 2, prevIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

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

                    // 앱 실행 버튼
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

        // ─── 알람 예약 (체크 ON일 때) ────────────────────────────────
        fun scheduleResetIfNeeded(context: Context, pkg: String) {
            val resetPrefs = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
            val hour = resetPrefs.getInt("${pkg}_hour", -1)
            val minute = resetPrefs.getInt("${pkg}_minute", 0)
            if (hour < 0) return // 초기화 시간 미설정이면 패스

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= Calendar.getInstance().timeInMillis) {
                    add(Calendar.DATE, 1)
                }
            }

            val intent = Intent(context, ResetReceiver::class.java).apply {
                putExtra("target_package", pkg)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, pkg.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            }
        }

        // ─── 알람 취소 (체크 OFF일 때) ───────────────────────────────
        fun cancelReset(context: Context, pkg: String) {
            val intent = Intent(context, ResetReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, pkg.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent)
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
                val nowDone = !donePrefs.getBoolean(pkg, false)
                donePrefs.edit().putBoolean(pkg, nowDone).apply()

                if (nowDone) {
                    // 체크 ON → 다음 초기화 시간에 알람 예약
                    scheduleResetIfNeeded(context, pkg)
                } else {
                    // 체크 OFF → 예약된 알람 취소
                    cancelReset(context, pkg)
                }

                updateAllWidgets(context)
                MiniWidget.updateAllWidgets(context)
                SmallWidget.updateAllWidgets(context)
            }
            ACTION_NEXT_PAGE -> {
                val id = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                if (id != -1) {
                    pageMap[id] = (pageMap[id] ?: 0) + 1
                    updateWidget(context, AppWidgetManager.getInstance(context), id)
                }
            }
            ACTION_PREV_PAGE -> {
                val id = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                if (id != -1) {
                    pageMap[id] = maxOf(0, (pageMap[id] ?: 0) - 1)
                    updateWidget(context, AppWidgetManager.getInstance(context), id)
                }
            }
        }
    }
}
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
import org.json.JSONArray
import org.json.JSONObject
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
        const val ACTION_SELECT_TAB = "com.example.homeworktracker.SELECT_TAB"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_WIDGET_ID = "extra_widget_id"
        const val EXTRA_CATEGORY = "extra_category"
        const val PAGE_SIZE = 9

        private val pageMap = mutableMapOf<Int, Int>()

        // 위젯별 선택된 탭 (프로세스 종료 후에도 유지)
        private fun getTab(context: Context, appWidgetId: Int): String =
            context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
                .getString("tab_$appWidgetId", "Daily") ?: "Daily"

        private fun setTab(context: Context, appWidgetId: Int, category: String) {
            context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
                .edit().putString("tab_$appWidgetId", category).apply()
        }

        // 완료 플래그 키 — Daily는 기존 키를 그대로 써서 하위 호환 유지
        fun doneKey(pkg: String, category: String): String =
            if (category == "Daily") pkg else "${pkg}_${category}_done"

        fun tasksOf(context: Context, pkg: String, category: String): List<String> =
            TaskRepository(context).tasks(pkg, category).map { it.title }

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HomeworkWidget::class.java))
            for (id in ids) updateWidget(context, manager, id)
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_homework)

            val category = getTab(context, appWidgetId)
            val repository = TaskRepository(context)
            val packages = repository.packages(category)
            val pm = context.packageManager

            val sortMode = context.getSharedPreferences("sort_prefs", Context.MODE_PRIVATE)
                .getInt("sort_mode", 0)
            val resetPrefs = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)

            val appInfos = packages.filter { pkg ->
                category != "Weekly" ||
                    (resetPrefs.getInt("${pkg}_Weekly_hour", -1) in 0..23 &&
                        resetPrefs.getInt("${pkg}_Weekly_minute", 0) in 0..59)
            }.mapNotNull { pkg ->
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    Triple(pm.getApplicationLabel(info).toString(), pkg, pm.getApplicationIcon(info))
                } catch (e: Exception) { null }
            }.let { list ->
                if (sortMode == 0) list.sortedBy { it.first }
                else if (category == "Event") list.sortedBy { EventDeadline.get(context, it.second).takeIf { time -> time > 0 } ?: Long.MAX_VALUE }
                else list.sortedWith(Comparator { a, b ->
                    val ah = resetPrefs.getInt("${a.second}_${category}_hour", -1)
                    val am = resetPrefs.getInt("${a.second}_${category}_minute", 0)
                    val bh = resetPrefs.getInt("${b.second}_${category}_hour", -1)
                    val bm = resetPrefs.getInt("${b.second}_${category}_minute", 0)
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

            applyTab(context, views, appWidgetId, R.id.tvTabDaily, "Daily", category, appWidgetId * 10 + 3)
            applyTab(context, views, appWidgetId, R.id.tvTabWeekly, "Weekly", category, appWidgetId * 10 + 4)
            applyTab(context, views, appWidgetId, R.id.tvTabEvent, "Event", category, appWidgetId * 10 + 5)

            if (appInfos.isEmpty()) {
                views.setViewVisibility(R.id.tvEmptyHint, android.view.View.VISIBLE)
                views.setTextViewText(R.id.tvEmptyHint,
                    when (category) {
                        "Weekly" -> "초기화 시간이 설정된 앱이 없습니다"
                        "Event" -> "이벤트에 등록된 앱이 없습니다"
                        else -> "데일리에 등록된 앱이 없습니다"
                    })
            } else {
                views.setViewVisibility(R.id.tvEmptyHint, android.view.View.GONE)
            }

            val launchPending = PendingIntent.getActivity(
                context, appWidgetId,
                Intent(context, HomeActivity::class.java).putExtra("category", category),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.vHeaderSpacer, launchPending)
            views.setOnClickPendingIntent(R.id.tvPageInfo, launchPending)

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
                    val isDone = repository.isDone(pkg, category)

                    views.setViewVisibility(rowId, android.view.View.VISIBLE)
                    val expired = category == "Event" && EventDeadline.allExpired(context, pkg)
                    views.setTextViewText(nameId, if (expired) "$name · 마감" else name)
                    views.setContentDescription(nameId, if (category == "Event") "$name, ${EventDeadline.label(context, pkg)}" else name)
                    views.setTextViewText(checkId, if (isDone) "✓" else "○")
                    views.setTextColor(checkId,
                        if (isDone) android.graphics.Color.parseColor("#3FB950")
                        else android.graphics.Color.parseColor("#8B949E")
                    )
                    views.setTextColor(nameId,
                        if (isDone) android.graphics.Color.parseColor("#3FB950")
                        else android.graphics.Color.parseColor("#F0F6FC")
                    )

                    try { views.setImageViewBitmap(iconId, drawableToBitmap(icon)) } catch (e: Exception) { }

                    val requestCode = appWidgetId * 100 + rowIndex
                    val toggleIntent = Intent(context, HomeworkWidget::class.java).apply {
                        action = ACTION_TOGGLE_DONE
                        putExtra(EXTRA_PACKAGE, pkg)
                        putExtra(EXTRA_WIDGET_ID, appWidgetId)
                        putExtra(EXTRA_CATEGORY, category)
                    }
                    val togglePending = PendingIntent.getBroadcast(
                        context, requestCode, toggleIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
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
                        views.setOnClickPendingIntent(rowId, launchAppPending)
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

        // ─── 탭 스타일 + 클릭 연결 ───────────────────────────────────
        private fun applyTab(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            viewId: Int,
            tabCategory: String,
            selectedCategory: String,
            requestCode: Int
        ) {
            val isSelected = tabCategory == selectedCategory
            views.setTextColor(viewId, android.graphics.Color.parseColor(
                if (isSelected) "#F0F6FC" else "#8B949E"))
            views.setInt(viewId, "setBackgroundColor",
                if (isSelected) android.graphics.Color.parseColor("#FF21262D")
                else android.graphics.Color.TRANSPARENT)

            val intent = Intent(context, HomeworkWidget::class.java).apply {
                action = ACTION_SELECT_TAB
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
                putExtra(EXTRA_CATEGORY, tabCategory)
            }
            views.setOnClickPendingIntent(viewId, PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
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
                val category = intent.getStringExtra(EXTRA_CATEGORY) ?: "Daily"
                val repository = TaskRepository(context)
                repository.setDone(pkg, category, !repository.isDone(pkg, category))
            }
            ACTION_SELECT_TAB -> {
                val id = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                val category = intent.getStringExtra(EXTRA_CATEGORY) ?: return
                if (id != -1) {
                    setTab(context, id, category)
                    pageMap[id] = 0
                    updateWidget(context, AppWidgetManager.getInstance(context), id)
                }
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

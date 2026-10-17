// HomeworkWidget.kt
package com.example.homeworktracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class HomeworkWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        const val ACTION_TOGGLE_DONE = "com.example.homeworktracker.TOGGLE_DONE"
        const val ACTION_SELECT_TAB = "com.example.homeworktracker.SELECT_TAB"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_WIDGET_ID = "extra_widget_id"
        const val EXTRA_CATEGORY = "extra_category"


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
            applyTab(context, views, appWidgetId, R.id.tvTabDaily, "Daily", category, appWidgetId * 10 + 3)
            applyTab(context, views, appWidgetId, R.id.tvTabWeekly, "Weekly", category, appWidgetId * 10 + 4)
            applyTab(context, views, appWidgetId, R.id.tvTabEvent, "Event", category, appWidgetId * 10 + 5)
            views.setTextViewText(R.id.tvEmptyHint, when (category) {
                "Weekly" -> "초기화 시간이 설정된 할 일이 없습니다"
                "Event" -> "등록된 이벤트 할 일이 없습니다"
                else -> "데일리에 등록된 앱이 없습니다"
            })
            WidgetCollections.bind(context, appWidgetManager, appWidgetId, views, "homework", category,
                HomeworkWidget::class.java, ACTION_TOGGLE_DONE, R.id.tvEmptyHint)
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

    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_TOGGLE_DONE -> WidgetCollections.handleAppClick(context, intent)
            ACTION_SELECT_TAB -> {
                val id = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                val category = intent.getStringExtra(EXTRA_CATEGORY) ?: return
                if (id != -1) {
                    setTab(context, id, category)
                    updateWidget(context, AppWidgetManager.getInstance(context), id)
                }
            }
        }
    }
}

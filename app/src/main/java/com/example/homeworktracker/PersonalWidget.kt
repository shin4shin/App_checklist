// PersonalWidget.kt — 개인 계획 전용 4x4 위젯
package com.example.homeworktracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class PersonalWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) updateWidget(context, appWidgetManager, appWidgetId)
    }

    companion object {
        const val ACTION_TOGGLE_DONE = "com.example.homeworktracker.PERSONAL_TOGGLE_DONE"
        const val ACTION_NEXT_PAGE = "com.example.homeworktracker.PERSONAL_NEXT_PAGE"
        const val ACTION_PREV_PAGE = "com.example.homeworktracker.PERSONAL_PREV_PAGE"
        const val EXTRA_PLAN_ID = "extra_plan_id"
        const val EXTRA_WIDGET_ID = "extra_widget_id"
        const val PAGE_SIZE = 7

        private val pageMap = mutableMapOf<Int, Int>()

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PersonalWidget::class.java))
            for (id in ids) updateWidget(context, manager, id)
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_personal)

            // 앱 화면과 같은 정렬: 미완료 먼저, 그다음 마감 임박 순
            val plans = PersonalPlanRepository(context).plans().sortedWith(
                compareBy<TaskItem> { it.done }
                    .thenBy { if (it.deadline > 0) it.deadline else Long.MAX_VALUE }
                    .thenBy { it.title }
            )

            views.setTextViewText(R.id.tvPersonalSummary, "${plans.count { it.done }}/${plans.size} 완료")

            val totalPages = if (plans.isEmpty()) 1 else (plans.size + PAGE_SIZE - 1) / PAGE_SIZE
            val currentPage = (pageMap[appWidgetId] ?: 0).coerceIn(0, totalPages - 1)
            pageMap[appWidgetId] = currentPage
            views.setTextViewText(R.id.tvPersonalPage, "${currentPage + 1}/$totalPages")

            val launchPending = PendingIntent.getActivity(
                context, appWidgetId,
                Intent(context, HomeActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.tvPersonalTitle, launchPending)
            views.setOnClickPendingIntent(R.id.tvPersonalSummary, launchPending)

            views.setOnClickPendingIntent(R.id.btnPersonalNext, pageIntent(context, appWidgetId, ACTION_NEXT_PAGE, 1))
            views.setOnClickPendingIntent(R.id.btnPersonalPrev, pageIntent(context, appWidgetId, ACTION_PREV_PAGE, 2))

            if (plans.isEmpty()) {
                views.setViewVisibility(R.id.tvPersonalEmpty, android.view.View.VISIBLE)
                views.setTextViewText(R.id.tvPersonalEmpty, "아직 계획이 없습니다")
            } else {
                views.setViewVisibility(R.id.tvPersonalEmpty, android.view.View.GONE)
            }

            val rowIds = listOf(
                listOf(R.id.prow1, R.id.pCheck1, R.id.pName1, R.id.pTime1),
                listOf(R.id.prow2, R.id.pCheck2, R.id.pName2, R.id.pTime2),
                listOf(R.id.prow3, R.id.pCheck3, R.id.pName3, R.id.pTime3),
                listOf(R.id.prow4, R.id.pCheck4, R.id.pName4, R.id.pTime4),
                listOf(R.id.prow5, R.id.pCheck5, R.id.pName5, R.id.pTime5),
                listOf(R.id.prow6, R.id.pCheck6, R.id.pName6, R.id.pTime6),
                listOf(R.id.prow7, R.id.pCheck7, R.id.pName7, R.id.pTime7),
            )
            val startIndex = currentPage * PAGE_SIZE

            rowIds.forEachIndexed { rowIndex, ids ->
                val (rowId, checkId, nameId, timeId) = ids
                val planIndex = startIndex + rowIndex
                if (planIndex >= plans.size) {
                    views.setViewVisibility(rowId, android.view.View.GONE)
                    return@forEachIndexed
                }
                val plan = plans[planIndex]
                views.setViewVisibility(rowId, android.view.View.VISIBLE)
                views.setTextViewText(checkId, if (plan.done) "✓" else "○")
                views.setTextColor(checkId, android.graphics.Color.parseColor(
                    if (plan.done) "#3FB950" else "#8B949E"))
                views.setTextViewText(nameId, plan.title)
                views.setTextColor(nameId, android.graphics.Color.parseColor(
                    if (plan.done) "#3FB950" else "#F0F6FC"))
                views.setTextViewText(timeId, EventDeadline.label(plan.deadline))
                views.setContentDescription(rowId,
                    "${plan.title}, ${if (plan.done) "완료" else "미완료"}, ${EventDeadline.label(plan.deadline)}")

                val toggleIntent = Intent(context, PersonalWidget::class.java).apply {
                    action = ACTION_TOGGLE_DONE
                    putExtra(EXTRA_PLAN_ID, plan.id)
                    putExtra(EXTRA_WIDGET_ID, appWidgetId)
                }
                views.setOnClickPendingIntent(rowId, PendingIntent.getBroadcast(
                    context, appWidgetId * 100 + rowIndex, toggleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun pageIntent(context: Context, appWidgetId: Int, action: String, offset: Int): PendingIntent {
            val intent = Intent(context, PersonalWidget::class.java).apply {
                this.action = action
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            return PendingIntent.getBroadcast(context, appWidgetId * 10 + offset, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_TOGGLE_DONE -> {
                val id = intent.getStringExtra(EXTRA_PLAN_ID) ?: return
                val repository = PersonalPlanRepository(context)
                val plan = repository.plans().firstOrNull { it.id == id } ?: return
                repository.setDone(id, !plan.done)
                updateAllWidgets(context)
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

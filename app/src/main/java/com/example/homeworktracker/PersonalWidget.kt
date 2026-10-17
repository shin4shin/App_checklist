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

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        updateWidget(context, manager, id)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) { PersonalWidgetOrder.schedule(context) }

    companion object {
        const val ACTION_TOGGLE_DONE = "com.example.homeworktracker.PERSONAL_TOGGLE_DONE"
        const val ACTION_RESORT = "com.example.homeworktracker.PERSONAL_RESORT"
        const val EXTRA_PLAN_ID = "extra_plan_id"
        const val EXTRA_WIDGET_ID = "extra_widget_id"

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PersonalWidget::class.java))
            for (id in ids) updateWidget(context, manager, id)
            PersonalWidgetOrder.schedule(context)
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_personal)
            val plans = PersonalPlanRepository(context).plans()
            views.setTextViewText(R.id.tvPersonalSummary, "${plans.count { it.done }}/${plans.size} 완료")
            views.setTextViewText(R.id.tvPersonalEmpty, "아직 계획이 없습니다")
            val launch = PendingIntent.getActivity(context, appWidgetId,
                Intent(context, HomeActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.tvPersonalTitle, launch)
            views.setOnClickPendingIntent(R.id.tvPersonalSummary, launch)
            WidgetCollections.bind(context, appWidgetManager, appWidgetId, views, "personal", "Daily",
                PersonalWidget::class.java, ACTION_TOGGLE_DONE, R.id.tvPersonalEmpty)
            PersonalWidgetOrder.schedule(context)
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
            }
            ACTION_RESORT -> updateAllWidgets(context)
        }
    }
}

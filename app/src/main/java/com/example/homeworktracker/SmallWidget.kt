// SmallWidget.kt
package com.example.homeworktracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class SmallWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    companion object {
        const val ACTION_TOGGLE_DONE = "com.example.homeworktracker.SMALL_TOGGLE_DONE"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_WIDGET_ID = "extra_widget_id"


        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SmallWidget::class.java))
            for (id in ids) updateWidget(context, manager, id)
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_small)
            views.setOnClickPendingIntent(R.id.tvSmallTitle, PendingIntent.getActivity(context, appWidgetId,
                Intent(context, HomeActivity::class.java).putExtra("category", "Daily"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            WidgetCollections.bind(context, appWidgetManager, appWidgetId, views, "small", "Daily",
                SmallWidget::class.java, ACTION_TOGGLE_DONE, R.id.widgetEmpty)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_DONE) WidgetCollections.handleAppClick(context, intent)
    }
}

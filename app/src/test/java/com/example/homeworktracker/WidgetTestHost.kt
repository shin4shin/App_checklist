package com.example.homeworktracker

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import org.robolectric.Shadows.shadowOf

// ShadowAppWidgetManager applies views without a host, which skips collection adapters.
fun widgetTestView(context: Context, manager: AppWidgetManager, id: Int): View {
    val shadow = shadowOf(manager)
    val infos = shadow.javaClass.getDeclaredField("widgetInfos").apply { isAccessible = true }.get(shadow) as Map<*, *>
    val info = infos[id]!!
    val views = info.javaClass.getDeclaredField("lastRemoteViews").apply { isAccessible = true }.get(info) as RemoteViews
    val host = AppWidgetHostView(context)
    return views.apply(context, host).also { host.addView(it) }
}

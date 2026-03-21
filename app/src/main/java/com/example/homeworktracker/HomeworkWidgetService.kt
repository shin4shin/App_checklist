// HomeworkWidgetService.kt
package com.example.homeworktracker

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class HomeworkWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return HomeworkListFactory(applicationContext, intent)
    }
}

class HomeworkListFactory(
    private val context: Context,
    private val intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val appInfos = mutableListOf<Triple<String, String, Drawable>>()
    private val appWidgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )

    override fun onCreate() { loadData() }
    override fun onDataSetChanged() { loadData() }
    override fun onDestroy() {}

    private fun loadData() {
        appInfos.clear()
        val prefs = context.getSharedPreferences("added_apps", Context.MODE_PRIVATE)
        val packages = prefs.getStringSet("apps", emptySet()) ?: emptySet()
        val pm = context.packageManager

        packages.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                Triple(pm.getApplicationLabel(info).toString(), pkg, pm.getApplicationIcon(info))
            } catch (e: Exception) { null }
        }.sortedBy { it.first }.also { appInfos.addAll(it) }
    }

    override fun getCount() = appInfos.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_list_item)
        val (name, pkg, icon) = appInfos[position]
        val isDone = context.getSharedPreferences("done_status", Context.MODE_PRIVATE)
            .getBoolean(pkg, false)

        views.setTextViewText(R.id.tvItemCheck, if (isDone) "✓" else "○")
        views.setTextViewText(R.id.tvItemName, name)
        views.setTextColor(R.id.tvItemCheck,
            if (isDone) android.graphics.Color.parseColor("#4CAF50")
            else android.graphics.Color.parseColor("#888888")
        )
        views.setTextColor(R.id.tvItemName,
            if (isDone) android.graphics.Color.parseColor("#4CAF50")
            else android.graphics.Color.WHITE
        )

        try {
            views.setImageViewBitmap(R.id.ivItemIcon, drawableToBitmap(icon))
        } catch (e: Exception) { }

        // fillInIntent에 패키지명 담기
        val fillIntent = Intent().apply {
            putExtra(HomeworkWidget.EXTRA_PACKAGE, pkg)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        views.setOnClickFillInIntent(R.id.widgetListItem, fillIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews {
        // 로딩 뷰를 실제 아이템과 동일하게 보이게 해서 깜빡임 최소화
        val views = RemoteViews(context.packageName, R.layout.widget_list_item)
        views.setTextViewText(R.id.tvItemCheck, "")
        views.setTextViewText(R.id.tvItemName, "")
        return views
    }
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = true

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
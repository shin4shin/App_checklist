package com.example.homeworktracker

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalWidgetTest {
    @Test fun resizeMovesClippedRowsOntoReachablePages() {
        val context = RuntimeEnvironment.getApplication()
        val repo = PersonalPlanRepository(context)
        (1..7).forEach { repo.save(TaskItem(title = "계획 $it")) }
        val manager = AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(PersonalWidget::class.java, R.layout.widget_personal)
        fun resize(height: Int) {
            manager.updateAppWidgetOptions(id, Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
            })
            PersonalWidget().onAppWidgetOptionsChanged(context, manager, id, manager.getAppWidgetOptions(id))
        }
        fun root() = shadowOf(manager).getViewFor(id)
        resize(430)
        assertEquals(View.VISIBLE, root().findViewById<View>(R.id.prow6).visibility)
        assertEquals(View.GONE, root().findViewById<View>(R.id.prow7).visibility)
        assertEquals("1/2", root().findViewById<TextView>(R.id.tvPersonalPage).text)
        PersonalWidget().onReceive(context, Intent(PersonalWidget.ACTION_NEXT_PAGE).putExtra(PersonalWidget.EXTRA_WIDGET_ID, id))
        assertEquals("계획 7", root().findViewById<TextView>(R.id.pName1).text)
        resize(464)
        assertEquals("1/1", root().findViewById<TextView>(R.id.tvPersonalPage).text)
        assertEquals(View.VISIBLE, root().findViewById<View>(R.id.prow7).visibility)
        resize(250)
        assertEquals("1/3", root().findViewById<TextView>(R.id.tvPersonalPage).text)
        assertEquals(View.GONE, root().findViewById<View>(R.id.prow4).visibility)
        assertEquals("일정 한눈에 보기", root().findViewById<TextView>(R.id.tvPersonalTitle).text)
    }
}

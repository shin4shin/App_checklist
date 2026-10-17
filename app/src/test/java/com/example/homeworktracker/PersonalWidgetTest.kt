package com.example.homeworktracker

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ListView
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
    @Test fun resizingKeepsEveryPlanInScrollableCollection() {
        val context = RuntimeEnvironment.getApplication()
        val repo = PersonalPlanRepository(context)
        (1..20).forEach { repo.save(TaskItem(title = "계획 $it")) }
        val manager = AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(PersonalWidget::class.java, R.layout.widget_personal)
        for (height in listOf(250, 430, 464)) {
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
            }
            PersonalWidget().onAppWidgetOptionsChanged(context, manager, id, options)
            val root = widgetTestView(context, manager, id)
            val list = root.findViewById<ListView>(R.id.widgetItems)
            assertEquals(20, list.adapter.count)
            val last = list.adapter.getView(19, null, list)
            assertNotNull(last.findViewById<TextView>(R.id.widgetName))
            assertEquals("일정 한눈에 보기", root.findViewById<TextView>(R.id.tvPersonalTitle).text)
        }
    }

    @Test fun checkStaysInPlaceUntilFiveMinutesAndAlarmUpdatesWidget() {
        val context = RuntimeEnvironment.getApplication()
        val repo = PersonalPlanRepository(context)
        val first = TaskItem(title = "A"); val second = TaskItem(title = "B")
        repo.save(first); repo.save(second)
        val manager = AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(PersonalWidget::class.java, R.layout.widget_personal)
        PersonalWidget().onReceive(context, Intent(PersonalWidget.ACTION_TOGGLE_DONE).putExtra(PersonalWidget.EXTRA_PLAN_ID, first.id))
        assertTrue(repo.plans().first { it.id == first.id }.done)
        val ids = listOf(first.id, second.id)
        assertEquals(ids, PersonalWidgetOrder.plans(context).map { it.id })
        val completedAt = context.getSharedPreferences("personal_widget_order", 0).getLong(first.id, 0L)
        assertEquals(ids, PersonalWidgetOrder.plans(context, completedAt + PersonalWidgetOrder.DELAY - 1).map { it.id })
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single {
            shadowOf(it.operation).savedIntent.action == PersonalWidget.ACTION_RESORT
        }
        assertEquals(completedAt + PersonalWidgetOrder.DELAY, alarm.triggerAtTime)
        assertEquals(ids.reversed(), PersonalWidgetOrder.plans(context, completedAt + PersonalWidgetOrder.DELAY).map { it.id })
        context.getSharedPreferences("personal_widget_order", 0).edit()
            .putLong(first.id, System.currentTimeMillis() - PersonalWidgetOrder.DELAY).commit()
        PersonalWidget().onReceive(context, shadowOf(alarm.operation).savedIntent)
        assertEquals(ids.reversed(), PersonalWidgetOrder.plans(context).map { it.id })
        val list = widgetTestView(context, manager, id).findViewById<ListView>(R.id.widgetItems)
        assertEquals("B", list.adapter.getView(0, null, list).findViewById<TextView>(R.id.widgetName).text)
    }

    @Test fun undoAndDeletionCancelPendingReorder() {
        val context = RuntimeEnvironment.getApplication()
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).createWidget(PersonalWidget::class.java, R.layout.widget_personal)
        val repo = PersonalPlanRepository(context)
        val plan = TaskItem(title = "할 일")
        repo.save(plan); repo.setDone(plan.id, true); repo.setDone(plan.id, false)
        assertFalse(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.any {
            shadowOf(it.operation).savedIntent.action == PersonalWidget.ACTION_RESORT
        })
        repo.setDone(plan.id, true); repo.delete(plan.id)
        assertFalse(context.getSharedPreferences("personal_widget_order", 0).contains(plan.id))
        assertFalse(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.any {
            shadowOf(it.operation).savedIntent.action == PersonalWidget.ACTION_RESORT
        })
    }
}

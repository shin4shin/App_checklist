package com.example.homeworktracker

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetCollectionsTest {
    @Test fun eventsSortAcrossAppsByDeadlineRegardlessOfAppSortMode() {
        val context = RuntimeEnvironment.getApplication()
        val other = "test.other.game"
        shadowOf(context.packageManager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = other
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = other; nonLocalizedLabel = "Other" }
        })
        val repo = TaskRepository(context)
        val now = System.currentTimeMillis()
        repo.addApp(context.packageName, "Event")
        repo.addApp(other, "Event")
        repo.saveTasks(context.packageName, "Event", listOf(
            TaskItem(title = "늦은 마감", deadline = now + 7200000),
            TaskItem(title = "미설정"), TaskItem(title = "마감됨", deadline = now - 1000)))
        repo.saveTasks(other, "Event", listOf(TaskItem(title = "임박", deadline = now + 3600000)))
        for (mode in listOf(0, 1)) {
            context.getSharedPreferences("sort_prefs", 0).edit().putInt("sort_mode", mode).commit()
            assertEquals(listOf("임박", "늦은 마감", "마감됨", "미설정"),
                WidgetCollections.rows(context, "homework", "Event").map { it.title })
        }
    }

    @Test fun weeklyAndEventShowIndividualTasksAndToggleOnlyTheirStableIds() {
        val context = RuntimeEnvironment.getApplication()
        val pkg = context.packageName
        val repo = TaskRepository(context)
        val manager = AppWidgetManager.getInstance(context)
        val widget = shadowOf(manager).createWidget(HomeworkWidget::class.java, R.layout.widget_homework)
        val controller = Robolectric.buildService(GameOverlayService::class.java).create()
        val build = GameOverlayService::class.java.getDeclaredMethod("buildOverlayView", String::class.java).apply { isAccessible = true }
        for (category in listOf("Weekly", "Event")) {
            repo.addApp(pkg, category)
            val one = TaskItem(title = "첫 할 일"); val two = TaskItem(title = "두 번째 할 일")
            repo.saveTasks(pkg, category, listOf(one, two))
            context.getSharedPreferences("reset_times", 0).edit().putInt("${pkg}_Weekly_hour", 0).commit()
            HomeworkWidget().onReceive(context, Intent(HomeworkWidget.ACTION_SELECT_TAB)
                .putExtra(HomeworkWidget.EXTRA_WIDGET_ID, widget).putExtra(HomeworkWidget.EXTRA_CATEGORY, category))
            val list = widgetTestView(context, manager, widget).findViewById<ListView>(R.id.widgetItems)
            assertEquals(2, list.adapter.count)
            assertEquals(one.title, list.adapter.getView(0, null, list).findViewById<TextView>(R.id.widgetName).text)
            HomeworkWidget().onReceive(context, Intent(HomeworkWidget.ACTION_TOGGLE_DONE)
                .putExtra(HomeworkWidget.EXTRA_PACKAGE, pkg).putExtra(HomeworkWidget.EXTRA_CATEGORY, category)
                .putExtra(WidgetCollections.TASK_ID, one.id))
            assertTrue(repo.tasks(pkg, category)[0].done)
            assertFalse(repo.tasks(pkg, category)[1].done)
            val root = build.invoke(controller.get(), pkg) as android.view.View
            val tasks = root.findViewById<LinearLayout>(R.id.taskContainer)
            val row = (0 until tasks.childCount).map { tasks.getChildAt(it) }.first {
                it.findViewById<TextView>(R.id.tvTaskName)?.text?.startsWith(one.title) == true
            }
            assertEquals("✓", row.findViewById<TextView>(R.id.tvTaskCheck).text)
            row.performClick()
            assertFalse(WidgetCollections.rows(context, "homework", category)[0].done)
            val updated = widgetTestView(context, manager, widget).findViewById<ListView>(R.id.widgetItems)
            assertEquals("○", updated.adapter.getView(0, null, updated).findViewById<TextView>(R.id.widgetCheck).text)
            repo.removeApp(pkg, category)
        }
        controller.destroy()
    }

    @Test @Config(sdk = [28]) fun olderAndroidFactoryRefreshesCompleteList() {
        val context = RuntimeEnvironment.getApplication()
        val repo = PersonalPlanRepository(context)
        val plans = (1..12).map { TaskItem(title = "계획 $it") }
        plans.forEach { repo.save(it) }
        val service = Robolectric.buildService(WidgetListService::class.java).create()
        val factory = service.get().onGetViewFactory(Intent().putExtra("kind", "personal"))
        factory.onCreate()
        assertEquals(12, factory.count)
        assertEquals(12, (0 until factory.count).map { factory.getItemId(it) }.distinct().size)
        repo.delete(plans.first().id)
        factory.onDataSetChanged()
        assertEquals(11, factory.count)
        assertNotNull(factory.getViewAt(10))
        factory.onDestroy(); service.destroy()
    }
}

package com.example.homeworktracker

import android.os.Looper
import android.widget.LinearLayout
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
class HomeDashboardTest {
    @Test fun dashboardCountsTasksAndLimitsUpcomingEvents() {
        val repo = TaskRepository(RuntimeEnvironment.getApplication())
        repo.addApp("game", "Daily")
        repo.saveTasks("game", "Daily", listOf(TaskItem(title = "A"), TaskItem(title = "B")))
        repo.setTaskDone("game", "Daily", repo.tasks("game", "Daily").first().id, true)
        repo.addApp("game", "Event")
        val now = System.currentTimeMillis()
        repo.saveTasks("game", "Event", (1..4).map { TaskItem(title = "E$it", deadline = now + it * 86_400_000L) } + TaskItem(title = "마감", deadline = now - 1))
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        assertEquals("50%", activity.findViewById<TextView>(R.id.tvProgressPercent).text)
        assertEquals(3, activity.findViewById<LinearLayout>(R.id.homeEvents).childCount)
        assertEquals(1, activity.findViewById<LinearLayout>(R.id.homePendingApps).childCount)
        activity.findViewById<TextView>(R.id.homeExpiredToggle).performClick()
        assertEquals(1, activity.findViewById<LinearLayout>(R.id.homeExpiredEvents).childCount)
        repo.setDone("game", "Daily", true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("100%", activity.findViewById<TextView>(R.id.tvProgressPercent).text)
        assertEquals("오늘 할 일을 모두 마쳤어요!", activity.findViewById<TextView>(R.id.tvHomeEncouragement).text)
        controller.pause().stop().destroy()
    }
}

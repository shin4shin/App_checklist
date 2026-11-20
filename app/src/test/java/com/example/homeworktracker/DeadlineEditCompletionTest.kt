package com.example.homeworktracker

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeadlineEditCompletionTest {
    @Test fun eventTimeChangeClearsCompletionButTitleAndSameTimeKeepIt() {
        val context = RuntimeEnvironment.getApplication()
        val repo = TaskRepository(context)
        val pkg = context.packageName
        repo.addApp(pkg, "Event")
        val task = TaskItem(title = "이벤트", deadline = System.currentTimeMillis() + 86_400_000L)
        repo.saveTasks(pkg, "Event", listOf(task))
        repo.setTaskDone(pkg, "Event", task.id, true)
        repo.saveTasks(pkg, "Event", listOf(task.copy(title = "이름 변경")))
        assertTrue(repo.tasks(pkg, "Event").single().done)
        repo.saveTasks(pkg, "Event", listOf(task.copy(deadline = task.deadline + 86_400_000L)))
        assertFalse(repo.tasks(pkg, "Event").single().done)
    }
    @Test fun personalTimeChangeClearsCompletionAndSyncStillUsesServerCompletion() {
        val context = RuntimeEnvironment.getApplication()
        val repo = PersonalPlanRepository(context)
        val task = TaskItem(title = "계획", deadline = System.currentTimeMillis() + 86_400_000L)
        repo.save(task); repo.setDone(task.id, true)
        repo.save(task.copy(title = "제목 수정"))
        assertTrue(repo.plans().single().done)
        repo.save(task.copy(deadline = task.deadline + 60_000L))
        assertFalse(repo.plans().single().done)
        repo.setDone(task.id, true)
        SyncManager(context).applyServerChanges(JSONArray().put(JSONObject().apply {
            put("packageName", "personal"); put("category", "Personal"); put("taskId", task.id)
            put("title", task.title); put("deadline", task.deadline + 120_000L); put("done", true)
        }), JSONArray())
        assertTrue(repo.plans().single().done)
    }
}

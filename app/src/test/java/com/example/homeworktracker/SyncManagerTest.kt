package com.example.homeworktracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncManagerTest {
    private lateinit var context: Context
    private lateinit var syncManager: SyncManager
    private lateinit var repository: TaskRepository

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(TaskRepository.STORE, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("reset_times", Context.MODE_PRIVATE).edit().clear().commit()
        syncManager = SyncManager(context)
        repository = TaskRepository(context)
    }

    @Test
    fun authSessionLifecycle() {
        assertFalse(syncManager.isLoggedIn())

        syncManager.setAuth("test_jwt_token", "usr_12345", "player@example.com")
        assertTrue(syncManager.isLoggedIn())
        assertEquals("player@example.com", syncManager.getUserEmail())

        syncManager.logout()
        assertFalse(syncManager.isLoggedIn())
    }

    @Test
    fun buildLocalPayload_serializesTasksAndSettings() {
        repository.addApp("com.hoyoverse.nap", "Daily")
        repository.addTask("com.hoyoverse.nap", "Daily", "카페 커피 마시기")

        val resetTimes = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
        resetTimes.edit()
            .putInt("com.hoyoverse.nap_Daily_hour", 4)
            .putInt("com.hoyoverse.nap_Daily_minute", 30)
            .commit()

        val (tasks, settings) = syncManager.buildLocalPayload()

        assertTrue(tasks.length() >= 1)
        var foundCoffee = false
        for (i in 0 until tasks.length()) {
            val t = tasks.getJSONObject(i)
            if (t.getString("title") == "카페 커피 마시기") {
                foundCoffee = true
                assertEquals("com.hoyoverse.nap", t.getString("packageName"))
                assertEquals("Daily", t.getString("category"))
                assertFalse(t.getBoolean("done"))
            }
        }
        assertTrue(foundCoffee)

        assertTrue(settings.length() >= 1)
        val s = settings.getJSONObject(0)
        assertEquals("com.hoyoverse.nap", s.getString("packageName"))
        assertEquals(270, s.getInt("dailyResetMinute")) // 4 * 60 + 30
    }

    @Test
    fun applyServerChanges_mergesIncomingTasksAndDeletions() {
        repository.addApp("com.hoyoverse.hkrpg", "Daily")
        val currentTasks = repository.tasks("com.hoyoverse.hkrpg", "Daily")
        val originalTaskId = currentTasks[0].id

        val serverTasks = JSONArray().apply {
            // 1. Update existing task to done
            put(JSONObject().apply {
                put("packageName", "com.hoyoverse.hkrpg")
                put("category", "Daily")
                put("taskId", originalTaskId)
                put("title", "일일 퀘스트 (서버 수정)")
                put("done", true)
            })
            // 2. Add new task from desktop widget
            put(JSONObject().apply {
                put("packageName", "com.hoyoverse.hkrpg")
                put("category", "Daily")
                put("taskId", "task_desktop_added")
                put("title", "데스크톱에서 추가한 숙제")
                put("done", false)
            })
        }

        val serverSettings = JSONArray().apply {
            put(JSONObject().apply {
                put("packageName", "com.hoyoverse.hkrpg")
                put("dailyResetMinute", 300) // 05:00
                put("weeklyResetDay", 2)
                put("weeklyResetMinute", 300)
            })
        }

        syncManager.applyServerChanges(serverTasks, serverSettings)

        val updatedTasks = repository.tasks("com.hoyoverse.hkrpg", "Daily")
        assertEquals(2, updatedTasks.size)

        val first = updatedTasks.find { it.id == originalTaskId }
        assertNotNull(first)
        assertEquals("일일 퀘스트 (서버 수정)", first!!.title)
        assertTrue(first.done)

        val added = updatedTasks.find { it.id == "task_desktop_added" }
        assertNotNull(added)
        assertEquals("데스크톱에서 추가한 숙제", added!!.title)
        assertFalse(added.done)

        val resetTimes = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
        assertEquals(5, resetTimes.getInt("com.hoyoverse.hkrpg_Daily_hour", -1))
        assertEquals(0, resetTimes.getInt("com.hoyoverse.hkrpg_Daily_minute", -1))
    }
    @Test fun unchangedTasksAreNotPushedAgainAndDeletesAreSent() {
        val original = JSONObject().put("packageName", "pkg").put("category", "Event")
            .put("taskId", "one").put("title", "일정").put("done", false).put("updatedAt", 1)
        val previous = JSONArray().put(original)
        val current = JSONArray().put(JSONObject(original.toString()).put("updatedAt", 999))
        assertEquals(0, syncManager.changes(current, previous, true).length())
        current.getJSONObject(0).put("done", true)
        assertEquals(1, syncManager.changes(current, previous, true).length())
        assertTrue(syncManager.changes(JSONArray(), previous, true).getJSONObject(0).getBoolean("isDeleted"))
    }
}

package com.example.homeworktracker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventImportClientTest {
    private fun response(): JSONObject = JSONObject().put("sources", JSONArray().put(
        JSONObject().put("game", "zzz").put("available", true).put("stale", false)
    )).put("events", JSONArray().put(JSONObject()
        .put("game", "zzz").put("id", "zzz_ennead_1").put("title", "한국어 이벤트")
        .put("importable", true).put("endAt", "2026-10-25T19:59:59Z")))

    @Test fun importsAbsoluteDeadlineAndExcludesExpiredEvents() {
        val deadline = Instant.parse("2026-10-25T19:59:59Z").toEpochMilli()
        val event = EventImportClient.parse(response().toString(), "zzz", deadline - 1).single()
        assertEquals("한국어 이벤트", event.title)
        assertEquals(deadline, event.deadline)
        assertTrue(EventImportClient.parse(response().toString(), "zzz", deadline).isEmpty())
    }

    @Test fun rejectsStaleOrWrongGameWithoutChangingExistingTasks() {
        val data = response()
        data.getJSONArray("sources").getJSONObject(0).put("stale", true)
        assertThrows(IllegalStateException::class.java) { EventImportClient.parse(data.toString(), "zzz", 0) }
        assertThrows(IllegalStateException::class.java) { EventImportClient.parse(response().toString(), "hsr", 0) }
    }

    @Test fun repeatImportPreservesCompletionAndManualTasks() {
        val event = EventImportClient.parse(response().toString(), "zzz", 0).single()
        val manual = TaskItem(title = "직접 입력")
        val current = listOf(manual, event.copy(done = true))
        val updated = event.copy(title = "수정된 제목", deadline = event.deadline + 3600000)
        val result = EventImportClient.merge(current, listOf(updated))
        assertEquals(2, result.size)
        assertEquals(manual, result[0])
        assertEquals(updated.copy(done = true), result[1])
        assertEquals(current, EventImportClient.merge(current, emptyList()))
    }
}

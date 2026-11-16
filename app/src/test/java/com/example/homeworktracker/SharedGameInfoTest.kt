package com.example.homeworktracker

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedGameInfoTest {
    @Test fun desktopGameRoundTripsNameIconAndResetWithoutAndroidInstallation() {
        val context = RuntimeEnvironment.getApplication()
        val pkg = "pc:shared-game"
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val icon = "data:image/png;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        val settings = JSONArray().put(JSONObject().apply {
            put("packageName", pkg); put("appName", "PC 게임"); put("appIcon", icon)
            put("dailyResetMinute", 330); put("weeklyResetDay", 6); put("weeklyResetMinute", 615)
        })
        val tasks = JSONArray().put(JSONObject().apply {
            put("packageName", pkg); put("category", "Daily"); put("taskId", "daily-1")
            put("title", "일일 퀘스트"); put("done", false); put("deadline", 0)
        })
        val sync = SyncManager(context)
        sync.applyServerChanges(tasks, settings)
        assertEquals("PC 게임", SharedGameInfo.resolve(context, pkg)?.name)
        assertEquals(4, SharedGameInfo.resolve(context, pkg)?.icon?.intrinsicWidth)
        assertTrue(TaskRepository(context).packages("Daily").contains(pkg))
        val payload = sync.buildLocalPayload().second
        val sent = (0 until payload.length()).map { payload.getJSONObject(it) }.first { it.getString("packageName") == pkg }
        assertEquals("PC 게임", sent.getString("appName"))
        assertEquals(icon, sent.getString("appIcon"))
        assertTrue(sent.isNull("isInstalled"))
        assertEquals(330, sent.getInt("dailyResetMinute"))
        assertEquals(6, sent.getInt("weeklyResetDay"))
        assertEquals(615, sent.getInt("weeklyResetMinute"))
        val reset = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
        assertEquals("6", reset.getString("${pkg}_Weekly_days", ""))
        reset.edit().putString("${pkg}_Weekly_days", "3").apply()
        val edited = sync.buildLocalPayload().second.getJSONObject(0)
        assertEquals(3, edited.getInt("weeklyResetDay"))
        SharedGameInfo.store(context, JSONArray().put(JSONObject().put("packageName", pkg).put("appIcon", "")))
        assertEquals(icon, SharedGameInfo.iconData(context, pkg))
        assertNull(SharedGameInfo.resolve(context, "com.removed.game"))
    }
    @Test fun identifierCannotOverwriteKnownNameOrBeSentBackAsName() {
        val context = RuntimeEnvironment.getApplication()
        val pkg = "pc:unknown-name"
        val sync = SyncManager(context)
        val bad = JSONArray().put(JSONObject().put("packageName", pkg).put("appName", pkg))
        sync.applyServerChanges(JSONArray(), bad)
        context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE).edit().putString("server_app_packages", "[\"$pkg\"]").apply()
        assertNull(SharedGameInfo.resolve(context, pkg))
        assertEquals("", sync.buildLocalPayload().second.getJSONObject(0).getString("appName"))
        SharedGameInfo.store(context, JSONArray().put(JSONObject().put("packageName", pkg).put("appName", "정상 이름")))
        SharedGameInfo.store(context, bad)
        assertEquals("정상 이름", SharedGameInfo.resolve(context, pkg)?.name)
        val current = sync.buildLocalPayload().second
        assertFalse(sync.acknowledgedSettings(current, bad).getJSONObject(0).has("appName"))
        assertEquals(1, sync.changes(current, sync.acknowledgedSettings(current, bad), false).length())
    }

}

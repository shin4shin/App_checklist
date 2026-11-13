package com.example.homeworktracker

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

class SyncManager(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)
    private val repository = TaskRepository(ctx)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_LAST_SYNC = "last_sync_time"
        private const val KEY_DEVICE_ID = "device_id"
    }

    fun isLoggedIn(): Boolean = !prefs.getString(KEY_TOKEN, null).isNullOrBlank()

    fun getUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)

    fun getLastSyncTime(): Long = prefs.getLong(KEY_LAST_SYNC, 0L)

    fun setAuth(token: String, userId: String, email: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USER_EMAIL, email)
            .apply()
    }

    fun logout() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USER_ID).remove(KEY_USER_EMAIL).apply()
    }

    private fun getOrCreateDeviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun ensureSession(): String {
        val existingToken = prefs.getString(KEY_TOKEN, null)
        if (!existingToken.isNullOrBlank()) return existingToken

        val deviceId = getOrCreateDeviceId()
        val res = SyncClient.loginAsDevice(deviceId)
        setAuth(res.token, res.user.id, res.user.email)
        return res.token
    }

    fun generatePairingCodeAsync(onSyncFailure: ((Throwable) -> Unit)? = null, callback: (Result<String>) -> Unit) {
        executor.execute {
            try {
                val token = ensureSession()
                val res = try {
                    SyncClient.generatePairingCode(token)
                } catch (_: SyncClient.UnauthorizedException) {
                    logout()
                    SyncClient.generatePairingCode(ensureSession())
                }
                mainHandler.post { callback(Result.success(res.code)) }
                syncNow().exceptionOrNull()?.let { error ->
                    mainHandler.post { onSyncFailure?.invoke(error) }
                }
            } catch (e: Exception) {
                mainHandler.post { callback(Result.failure(e)) }
            }
        }
    }

    fun loginWithGoogleAsync(idToken: String, callback: (Result<String>) -> Unit) {
        executor.execute {
            try {
                val res = SyncClient.loginWithGoogle(idToken)
                setAuth(res.token, res.user.id, res.user.email)
                mainHandler.post { callback(Result.success(res.user.email)) }
            } catch (e: Exception) {
                mainHandler.post { callback(Result.failure(e)) }
            }
        }
    }

    private val iconCache = mutableMapOf<String, String>()
    private fun appIcon(pkg: String): String = iconCache.getOrPut(pkg + runCatching { ctx.packageManager.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)) {
        runCatching {
            val icon = ctx.packageManager.getApplicationIcon(pkg)
            val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                icon.setBounds(0, 0, 64, 64)
                icon.draw(android.graphics.Canvas(bitmap))
                val output = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                "data:image/png;base64," + android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
            } finally { bitmap.recycle() }
        }.getOrDefault("")
    }
    fun buildLocalPayload(): Pair<JSONArray, JSONArray> {
        val tasksArray = JSONArray()
        val settingsArray = JSONArray()
        val resetTimes = ctx.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val knownApps = JSONArray(prefs.getString("server_app_packages", "[]"))
        val packages = repository.packages().toSet() + (0 until knownApps.length()).map { knownApps.getString(it) }
        for (pkg in packages) {
            for (category in TaskRepository.categories) {
                val items = repository.tasks(pkg, category)
                if (items.isEmpty() && pkg in repository.packages(category) && category == "Daily") {
                    val appName = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    tasksArray.put(JSONObject().apply {
                        put("packageName", pkg)
                        put("category", category)
                        put("taskId", "__app_completion__")
                        put("title", appName)
                        put("done", repository.isDone(pkg, category))
                        put("deadline", 0)
                        put("routineDays", 0)
                        put("resetMinute", -1)
                        put("updatedAt", now)
                        put("isDeleted", false)
                    })
                }
                for (item in items) {
                    tasksArray.put(JSONObject().apply {
                        put("packageName", pkg)
                        put("category", category)
                        put("taskId", item.id)
                        put("title", item.title)
                        put("done", item.done)
                        put("deadline", item.deadline)
                        put("routineDays", item.routineDays)
                        put("resetMinute", item.resetMinute)
                        put("updatedAt", now)
                        put("isDeleted", false)
                    })
                }
            }

            val hour = resetTimes.getInt("${pkg}_Daily_hour", 4)
            val minute = resetTimes.getInt("${pkg}_Daily_minute", 0)
            val weeklyDay = resetTimes.getInt("${pkg}_Weekly_day", 2)
            val weeklyHour = resetTimes.getInt("${pkg}_Weekly_hour", 4)
            val weeklyMinute = resetTimes.getInt("${pkg}_Weekly_minute", 0)

            settingsArray.put(JSONObject().apply {
                put("packageName", pkg)
                put("appIcon", appIcon(pkg))
                put("isInstalled", runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.isSuccess)
                put("appName", runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg))
                put("dailyResetMinute", hour * 60 + minute)
                put("weeklyResetDay", weeklyDay)
                put("weeklyResetMinute", weeklyHour * 60 + weeklyMinute)
                put("updatedAt", now)
            })
        }

        for (item in PersonalPlanRepository(ctx).plans()) {
            tasksArray.put(JSONObject().apply {
                put("packageName", "personal")
                put("category", "Personal")
                put("taskId", item.id)
                put("title", item.title)
                put("done", item.done)
                put("deadline", item.deadline)
                put("routineDays", 0)
                put("resetMinute", -1)
                put("updatedAt", now)
                put("isDeleted", false)
            })
        }
        return Pair(tasksArray, settingsArray)
    }

    fun applyServerChanges(serverTasks: JSONArray, serverSettings: JSONArray) {
        val resetTimes = ctx.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
        val resetEdit = resetTimes.edit()

        val byGroup = mutableMapOf<Pair<String, String>, MutableList<TaskItem>>()
        val deletedIds = mutableSetOf<String>()

        for (i in 0 until serverTasks.length()) {
            val obj = serverTasks.getJSONObject(i)
            val pkg = obj.getString("packageName")
            val cat = obj.getString("category")
            val id = obj.getString("taskId")
            val isDeleted = obj.optBoolean("isDeleted", false)
            if (id == "__app_completion__" && cat in listOf("Daily", "Weekly")) {
                if (!isDeleted) {
                    if (pkg !in repository.packages(cat)) repository.importTasks(pkg, cat, emptyList())
                    if (repository.tasks(pkg, cat).isEmpty()) repository.setDone(pkg, cat, obj.optBoolean("done", false))
                }
                continue
            }
            if (pkg == "personal" && cat == "Personal") {
                val personal = PersonalPlanRepository(ctx)
                if (isDeleted) personal.delete(id)
                else {
                    val existing = personal.plans().find { it.id == id }
                    val title = obj.getString("title")
                    val deadline = obj.optLong("deadline", 0L)
                    val done = obj.optBoolean("done", false)
                    if (existing == null || existing.title != title || existing.deadline != deadline) personal.save(TaskItem(id, title, deadline = deadline))
                    if (existing?.done != done) personal.setDone(id, done)
                }
                continue
            }

            if (isDeleted) {
                deletedIds.add("$pkg/$cat/$id")
                byGroup.getOrPut(Pair(pkg, cat)) { mutableListOf() }
            } else {
                val item = TaskItem(
                    id = id,
                    title = obj.getString("title"),
                    done = obj.optBoolean("done", false),
                    deadline = obj.optLong("deadline", 0L),
                    routineDays = obj.optLong("routineDays", 0L),
                    resetMinute = obj.optInt("resetMinute", -1)
                )
                byGroup.getOrPut(Pair(pkg, cat)) { mutableListOf() }.add(item)
            }
        }

        for ((group, incomingList) in byGroup) {
            val (pkg, cat) = group
            val current = repository.tasks(pkg, cat).toMutableList()

            current.removeAll { "$pkg/$cat/${it.id}" in deletedIds }

            for (incoming in incomingList) {
                val index = current.indexOfFirst { it.id == incoming.id }
                if (index >= 0) {
                    current[index] = incoming
                } else {
                    current.add(incoming)
                }
            }

            if (current != repository.tasks(pkg, cat) || pkg !in repository.packages(cat)) repository.importTasks(pkg, cat, current)
        }

        for (i in 0 until serverSettings.length()) {
            val obj = serverSettings.getJSONObject(i)
            val pkg = obj.getString("packageName")
            val dailyMinute = obj.optInt("dailyResetMinute", 240)
            val weeklyDay = obj.optInt("weeklyResetDay", 2)
            val weeklyMinute = obj.optInt("weeklyResetMinute", 240)

            resetEdit.putInt("${pkg}_Daily_hour", dailyMinute / 60)
                .putInt("${pkg}_Daily_minute", dailyMinute % 60)
                .putInt("${pkg}_Weekly_day", weeklyDay)
                .putInt("${pkg}_Weekly_hour", weeklyMinute / 60)
                .putInt("${pkg}_Weekly_minute", weeklyMinute % 60)
        }
        resetEdit.apply()

        repository.refresh("")
    }

    internal fun changes(current: JSONArray, previous: JSONArray, tasks: Boolean): JSONArray {
        fun key(item: JSONObject) = if (tasks) "${item.getString("packageName")}/${item.getString("category")}/${item.getString("taskId")}" else item.getString("packageName")
        fun values(item: JSONObject) = item.keys().asSequence().filter { it != "updatedAt" }.associateWith { item.get(it).toString() }
        val old = (0 until previous.length()).map { previous.getJSONObject(it) }.associateBy { key(it) }.toMutableMap()
        val result = JSONArray()
        for (i in 0 until current.length()) {
            val item = current.getJSONObject(i)
            val before = old.remove(key(item))
            if (before == null || values(before) != values(item)) result.put(item)
        }
        if (tasks) old.values.forEach {
            result.put(JSONObject(it.toString()).put("isDeleted", true).put("updatedAt", System.currentTimeMillis()))
        }
        return result
    }

    internal fun acknowledgedSettings(current: JSONArray, remote: JSONArray): JSONArray {
        val server = (0 until remote.length()).map { remote.getJSONObject(it) }.associateBy { it.getString("packageName") }
        return JSONArray().apply {
            for (index in 0 until current.length()) {
                val item = JSONObject(current.getJSONObject(index).toString())
                if (item.optString("appIcon").isNotEmpty() && server[item.getString("packageName")]?.optString("appIcon").isNullOrEmpty()) {
                    item.put("appIcon", "")
                }
                val confirmed = server[item.getString("packageName")]
                if (item.has("isInstalled") && (confirmed == null || confirmed.isNull("isInstalled") || confirmed.optBoolean("isInstalled") != item.optBoolean("isInstalled"))) item.remove("isInstalled")
                put(item)
            }
        }
    }

    fun syncNow(): Result<Long> {
        return try {
            val token = ensureSession()

            val (tasks, settings) = buildLocalPayload()
            val previousTasks = JSONArray(prefs.getString("sent_tasks", "[]"))
            val previousSettings = JSONArray(prefs.getString("sent_settings", "[]"))
            val cleanupKey = "mobile_cleanup_v1_${prefs.getString(KEY_USER_ID, "")}" 
            val pendingTasks = changes(tasks, previousTasks, true)
            if (!prefs.getBoolean(cleanupKey, false)) {
                val remote = SyncClient.sync(token, JSONArray(), JSONArray(), 0L)
                fun key(obj: JSONObject) = listOf(obj.getString("packageName"), obj.getString("category"), obj.getString("taskId"))
                val localKeys = (0 until tasks.length()).map { key(tasks.getJSONObject(it)) }.toSet()
                val now = System.currentTimeMillis()
                for (i in 0 until remote.serverTasks.length()) {
                    val item = remote.serverTasks.getJSONObject(i)
                    if (!item.optBoolean("isDeleted") && key(item) !in localKeys) {
                        pendingTasks.put(JSONObject(item.toString()).put("isDeleted", true)
                            .put("updatedAt", maxOf(now, item.optLong("updatedAt") + 1)))
                    }
                }
            }
            val response = SyncClient.sync(token, pendingTasks, changes(settings, previousSettings, false), 0L)
            applyServerChanges(response.serverTasks, response.serverSettings)
            prefs.edit().putString("server_app_packages", JSONArray((0 until response.serverSettings.length()).map { response.serverSettings.getJSONObject(it).getString("packageName") }).toString()).apply()
            val (savedTasks, savedSettings) = buildLocalPayload()
            prefs.edit().putBoolean(cleanupKey, true).putLong(KEY_LAST_SYNC, response.syncedAt)
                .putString("sent_tasks", savedTasks.toString())
                .putString("sent_settings", acknowledgedSettings(savedSettings, response.serverSettings).toString()).apply()
            Result.success(response.syncedAt)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun syncNowAsync(callback: (Result<Long>) -> Unit) {
        executor.execute {
            val result = syncNow()
            mainHandler.post { callback(result) }
        }
    }
}









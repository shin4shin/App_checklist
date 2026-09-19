package com.example.homeworktracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TaskItem(val id: String = UUID.randomUUID().toString(), val title: String, val done: Boolean = false, val deadline: Long = 0L)

/** One source of truth for membership, stable task IDs and completion on every surface. */
class TaskRepository(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    companion object {
        const val STORE = "checklist_v2"
        val categories = listOf("Daily", "Weekly", "Event")
        private val lock = Any()
    }

    init { synchronized(lock) { migrate(); migrateEventDeadlines() } }

    private fun migrate() {
        if (prefs.contains("data")) return
        val root = JSONObject()
        val oldTasks = context.getSharedPreferences("app_tasks", Context.MODE_PRIVATE)
        val oldDone = context.getSharedPreferences("done_status", Context.MODE_PRIVATE)
        val packages = context.getSharedPreferences("added_apps", Context.MODE_PRIVATE)
            .getStringSet("apps", emptySet()).orEmpty()
        for (pkg in packages) {
            val raw = oldTasks.getString(pkg, "{}").orEmpty()
            // Keep the original preferences intact as a migration backup.
            val old = try {
                if (raw.trim().startsWith("[")) JSONObject().put("Daily", JSONArray(raw)) else JSONObject(raw)
            } catch (error: org.json.JSONException) {
                android.util.Log.e("TaskRepository", "Invalid legacy tasks for $pkg; original data retained", error)
                JSONObject()
            }
            val app = JSONObject()
            for (category in categories) {
                val names = old.optJSONArray(category) ?: JSONArray()
                val allDone = oldDone.getBoolean(HomeworkWidget.doneKey(pkg, category), false)
                val items = (0 until names.length()).map { index ->
                    TaskItem(title = names.getString(index), done = allDone || oldDone.getBoolean("${pkg}_${category}_$index", false))
                }
                // Preserve the former shared lists; subsequent additions/deletions are independent.
                app.put(category, encode(items, allDone))
            }
            root.put(pkg, app)
        }
        check(prefs.edit().putString("data", root.toString()).commit()) { "체크리스트 이전에 실패했습니다" }
        val resetTimes = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
        val resetEdit = resetTimes.edit()
        for (pkg in packages) {
            if (!resetTimes.contains("${pkg}_Daily_hour") && resetTimes.contains("${pkg}_hour")) {
                resetEdit.putInt("${pkg}_Daily_hour", resetTimes.getInt("${pkg}_hour", -1))
                    .putInt("${pkg}_Daily_minute", resetTimes.getInt("${pkg}_minute", 0))
            }
        }
        resetEdit.apply()
    }

    private fun migrateEventDeadlines() {
        val data = root()
        val times = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
        val oldKeys = mutableListOf<String>()
        for (pkg in data.keys()) {
            val key = "${pkg}_Event_deadline"
            if (!times.contains(key)) continue
            val tasks = data.optJSONObject(pkg)?.optJSONObject("Event")?.optJSONArray("tasks")
            if (tasks != null) for (i in 0 until tasks.length()) {
                val task = tasks.getJSONObject(i)
                if (!task.has("deadline")) task.put("deadline", times.getLong(key, 0L))
            }
            oldKeys.add(key)
        }
        if (oldKeys.isNotEmpty()) {
            check(prefs.edit().putString("data", data.toString()).commit())
            times.edit().apply { oldKeys.forEach { remove(it) } }.apply()
        }
    }

    private fun root() = JSONObject(prefs.getString("data", "{}")!!)
    private fun encode(items: List<TaskItem>, emptyDone: Boolean = false): JSONObject = JSONObject()
        .put("emptyDone", emptyDone).put("tasks", JSONArray().apply {
            items.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("done", it.done).put("deadline", it.deadline)) }
        })

    private fun decode(group: JSONObject?): List<TaskItem> {
        val array = group?.optJSONArray("tasks") ?: return emptyList()
        return (0 until array.length()).map { i -> array.getJSONObject(i).let {
            TaskItem(it.getString("id"), it.getString("title"), it.optBoolean("done"), it.optLong("deadline"))
        } }
    }

    fun packages(category: String? = null): Set<String> = synchronized(lock) {
        val root = root()
        root.keys().asSequence().filter { category == null || root.getJSONObject(it).has(category) }.toSet()
    }

    fun tasks(pkg: String, category: String): List<TaskItem> = synchronized(lock) {
        decode(root().optJSONObject(pkg)?.optJSONObject(category))
    }

    fun isDone(pkg: String, category: String): Boolean = synchronized(lock) {
        val group = root().optJSONObject(pkg)?.optJSONObject(category) ?: return false
        val items = decode(group)
        if (items.isEmpty()) group.optBoolean("emptyDone") else items.all { it.done }
    }

    private fun change(pkg: String, edit: (JSONObject) -> Unit) {
        synchronized(lock) {
            val root = root()
            edit(root)
            prefs.edit().putString("data", root.toString()).apply()
        }
        refresh(pkg)
    }

    fun addApp(pkg: String, category: String) {
        require(category in categories)
        change(pkg) { root ->
            val app = root.optJSONObject(pkg) ?: JSONObject().also { root.put(pkg, it) }
            if (!app.has(category)) app.put(category, encode(listOf(TaskItem(title = when (category) {
                "Weekly" -> "주간 퀘스트"
                "Event" -> "이벤트 미션"
                else -> "일일 퀘스트"
            }))))
        }
    }

    fun addTask(pkg: String, category: String, title: String) {
        require(category in categories)
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        change(pkg) { root ->
            val app = root.optJSONObject(pkg) ?: return@change
            app.put(category, encode(decode(app.optJSONObject(category)) + TaskItem(title = trimmed)))
        }
    }

    fun removeApp(pkg: String, category: String) {
        ResetScheduler.cancel(context, pkg, category)
        val times = context.getSharedPreferences("reset_times", Context.MODE_PRIVATE)
        times.edit().remove("${pkg}_${category}_hour").remove("${pkg}_${category}_minute")
            .remove("${pkg}_${category}_days").remove("${pkg}_${category}_deadline").apply()
        change(pkg) { root ->
            root.optJSONObject(pkg)?.let { app ->
                app.remove(category)
                if (app.length() == 0) root.remove(pkg)
            }
        }
    }

    fun saveTasks(pkg: String, category: String, items: List<TaskItem>) {
        change(pkg) { root ->
            val app = root.optJSONObject(pkg) ?: return@change
            if (!app.has(category)) return@change
            val latest = decode(app.optJSONObject(category)).associateBy { it.id }
            app.put(category, encode(items.map { it.copy(done = latest[it.id]?.done ?: false) }))
        }

        if (category == "Event") ResetScheduler.schedule(context, pkg, category)
    }

    fun setTaskDone(pkg: String, category: String, id: String, done: Boolean) = change(pkg) { root ->
        val app = root.optJSONObject(pkg) ?: return@change
        if (!app.has(category)) return@change
        app.put(category, encode(decode(app.optJSONObject(category)).map { if (it.id == id) it.copy(done = done) else it }))
    }

    fun setDone(pkg: String, category: String, done: Boolean) = change(pkg) { root ->
        val app = root.optJSONObject(pkg) ?: return@change
        if (app.has(category)) app.put(category, encode(decode(app.optJSONObject(category)).map { it.copy(done = done) }, done))
    }

    fun refresh(pkg: String) {
        HomeworkWidget.updateAllWidgets(context)
        MiniWidget.updateAllWidgets(context)
        SmallWidget.updateAllWidgets(context)
        GameOverlayService.refreshIfShowing(pkg)
    }
}

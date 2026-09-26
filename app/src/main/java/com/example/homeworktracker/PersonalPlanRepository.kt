package com.example.homeworktracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Personal plans are isolated from app membership, completion and reset schedules. */
class PersonalPlanRepository(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("personal_plans", 0)
    companion object { private val lock = Any() }
    fun plans(): List<TaskItem> = synchronized(lock) {
        val array = JSONArray(prefs.getString("plans", "[]"))
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            TaskItem(it.getString("id"), it.getString("title"), it.optBoolean("done"), it.optLong("deadline"))
        } }
    }
    private fun change(edit: (List<TaskItem>) -> List<TaskItem>) {
        synchronized(lock) {
            val array = JSONArray()
            edit(plans()).forEach { item -> array.put(JSONObject().put("id", item.id).put("title", item.title)
                .put("done", item.done).put("deadline", item.deadline)) }
            prefs.edit().putString("plans", array.toString()).apply()
        }
        PersonalWidget.updateAllWidgets(context)
    }
    fun save(item: TaskItem) {
        require(item.title.isNotBlank())
        require(item.deadline >= 0)
        change { items ->
            val normalized = item.copy(title = item.title.trim())
            if (items.any { it.id == item.id }) items.map { if (it.id == item.id) normalized.copy(done = it.done) else it }
            else items + normalized.copy(done = false)
        }
    }
    fun setDone(id: String, done: Boolean) = change { items -> items.map { if (it.id == id) it.copy(done = done) else it } }
    fun delete(id: String) = change { items -> items.filterNot { it.id == id } }
}

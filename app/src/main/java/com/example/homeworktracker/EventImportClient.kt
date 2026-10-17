package com.example.homeworktracker

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

object EventImportClient {
    fun gameForPackage(pkg: String): String? = when (pkg.lowercase()) {
        "com.hoyoverse.nap" -> "zzz"
        "com.hoyoverse.hkrpgoversea" -> "hsr"
        else -> null
    }

    fun fetch(game: String): List<TaskItem> {
        require(game in listOf("zzz", "hsr"))
        val connection = URL("https://events.yulda.kr/v1/events?game=$game").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            check(connection.responseCode == 200)
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { parse(it.readText(), game) }
        } finally { connection.disconnect() }
    }

    fun parse(json: String, game: String, now: Long = System.currentTimeMillis()): List<TaskItem> {
        val root = JSONObject(json)
        val sources = root.getJSONArray("sources")
        check((0 until sources.length()).any { index ->
            val source = sources.getJSONObject(index)
            source.getString("game") == game && source.getBoolean("available") && !source.getBoolean("stale")
        })
        val events = root.getJSONArray("events")
        val ids = mutableSetOf<String>()
        return (0 until events.length()).mapNotNull { index ->
            val event = events.getJSONObject(index)
            check(event.getString("game") == game)
            if (!event.getBoolean("importable")) return@mapNotNull null
            val id = event.getString("id")
            val title = event.getString("title").trim()
            check(id.isNotBlank() && title.isNotBlank() && ids.add(id))
            val deadline = Instant.parse(event.getString("endAt")).toEpochMilli()
            if (deadline <= now) null else TaskItem(id = "import:$id", title = title, deadline = deadline)
        }
    }

    fun merge(current: List<TaskItem>, incoming: List<TaskItem>): List<TaskItem> {
        val result = current.toMutableList()
        incoming.forEach { event ->
            val index = result.indexOfFirst { it.id == event.id }
            if (index < 0) result.add(event)
            else result[index] = event.copy(done = result[index].done)
        }
        return result
    }
}

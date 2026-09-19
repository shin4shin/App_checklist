package com.example.homeworktracker

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale

object EventDeadline {
    /** A day means 24 elapsed hours; all input must be consumed, with each unit used once. */
    fun durationMillis(text: String): Long? {
        val pattern = Regex("(\\d+)\\s*(일|시간|분)")
        val matches = pattern.findAll(text.trim()).toList()
        if (matches.isEmpty() || pattern.replace(text.trim(), "").isNotBlank()) return null
        if (matches.map { it.groupValues[2] }.distinct().size != matches.size) return null
        return try {
            matches.fold(0L) { sum, match ->
                val unit = when (match.groupValues[2]) { "일" -> 86_400_000L; "시간" -> 3_600_000L; else -> 60_000L }
                Math.addExact(sum, Math.multiplyExact(match.groupValues[1].toLong(), unit))
            }.takeIf { it in 60_000L..(3650L * 86_400_000L) }
        } catch (_: ArithmeticException) { null } catch (_: NumberFormatException) { null }
    }

    fun get(context: Context, pkg: String): Long {
        val deadlines = TaskRepository(context).tasks(pkg, "Event").map { it.deadline }.filter { it > 0 }
        return deadlines.filter { it > System.currentTimeMillis() }.minOrNull() ?: deadlines.maxOrNull() ?: 0L
    }

    fun allExpired(context: Context, pkg: String, now: Long = System.currentTimeMillis()): Boolean {
        val tasks = TaskRepository(context).tasks(pkg, "Event")
        return tasks.isNotEmpty() && tasks.all { it.deadline in 1..now }
    }

    fun calculate(now: Long, duration: Long): Long {
        val target = Math.addExact(now, duration)
        val calendar = Calendar.getInstance().apply { timeInMillis = target }
        return target - calendar.get(Calendar.MINUTE) * 60_000L -
            calendar.get(Calendar.SECOND) * 1_000L - calendar.get(Calendar.MILLISECOND)
    }

    fun save(context: Context, packages: List<String>, duration: Long?, now: Long = System.currentTimeMillis()) {
        val deadline = duration?.let { calculate(now, it) }
        val repository = TaskRepository(context)
        val valid = repository.packages("Event")
        for (pkg in packages.filter { it in valid }) {
            repository.saveTasks(pkg, "Event", repository.tasks(pkg, "Event").map { it.copy(deadline = deadline ?: 0L) })
        }
    }

    fun label(context: Context, pkg: String, now: Long = System.currentTimeMillis()): String {
        val tasks = TaskRepository(context).tasks(pkg, "Event")
        if (tasks.size <= 1) return label(tasks.firstOrNull()?.deadline ?: 0L, now)
        val expired = tasks.count { it.deadline in 1..now }
        val upcoming = tasks.map { it.deadline }.filter { it > now }.minOrNull()
        return if (upcoming != null) "다음 마감 ${remaining(upcoming, now)} · 마감 $expired/${tasks.size}"
            else "마감 $expired/${tasks.size} · 미설정 ${tasks.count { it.deadline <= 0 }}"
    }

    fun label(deadline: Long, now: Long = System.currentTimeMillis()): String = when {
        deadline <= 0 -> "마감 미설정"
        deadline <= now -> "마감 · ${format(deadline)}"
        else -> "${remaining(deadline, now)} · ${format(deadline)}"
    }

    /** 마감까지 남은 기간. 정각 단위로 저장하므로 일·시간으로 끊고, 1시간 미만만 분으로 보여준다. */
    fun remaining(deadline: Long, now: Long = System.currentTimeMillis()): String {
        val diff = deadline - now
        if (diff <= 0) return "마감"
        val days = diff / 86_400_000L
        val hours = diff % 86_400_000L / 3_600_000L
        return when {
            days > 0 -> "${days}일 ${hours}시간 남음"
            hours > 0 -> "${hours}시간 남음"
            else -> "${diff % 3_600_000L / 60_000L}분 남음"
        }
    }

    fun format(time: Long): String = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREAN).format(Date(time))
}

package com.example.homeworktracker

import android.content.Context

object PlannerMode {
    fun isPersonal(context: Context) = context.getSharedPreferences("planner_mode", 0).getBoolean("personal", false)
    fun setPersonal(context: Context, personal: Boolean) {
        context.getSharedPreferences("planner_mode", 0).edit().putBoolean("personal", personal).apply()
    }
}

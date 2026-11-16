package com.example.homeworktracker

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import org.json.JSONArray

object SharedGameInfo {
    fun isDesktop(pkg: String) = pkg.startsWith("pc:")
    fun store(context: Context, settings: JSONArray) {
        val edit = context.getSharedPreferences("shared_pc_games", Context.MODE_PRIVATE).edit()
        for (i in 0 until settings.length()) {
            val item = settings.getJSONObject(i)
            val pkg = item.getString("packageName")
            if (!isDesktop(pkg)) continue
            val name = item.optString("appName").trim()
            if (name.isNotEmpty() && !name.startsWith("pc:")) edit.putString("${pkg}_name", name)
            if (item.optString("appIcon").isNotBlank()) edit.putString("${pkg}_icon", item.getString("appIcon"))
        }
        edit.apply()
    }
    fun iconData(context: Context, pkg: String): String = context.getSharedPreferences("shared_pc_games", Context.MODE_PRIVATE).getString("${pkg}_icon", "") ?: ""
    fun packages(context: Context) = context.getSharedPreferences("shared_pc_games", Context.MODE_PRIVATE).all.keys.filter { it.endsWith("_name") }.map { it.removeSuffix("_name") }
    fun resolve(context: Context, pkg: String): AppInfo? {
        if (!isDesktop(pkg)) return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            AppInfo(pm.getApplicationLabel(info).toString(), pkg, pm.getApplicationIcon(info))
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
        val name = context.getSharedPreferences("shared_pc_games", Context.MODE_PRIVATE).getString("${pkg}_name", null) ?: return null
        if (name.isBlank() || name.startsWith("pc:")) return null
        val icon = runCatching {
            val bytes = Base64.decode(iconData(context, pkg).substringAfter(","), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { BitmapDrawable(context.resources, it) }
        }.getOrNull() ?: context.packageManager.defaultActivityIcon
        return AppInfo(name, pkg, icon)
    }
}

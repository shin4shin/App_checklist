// BootReceiver.kt
package com.example.homeworktracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val packages = context.getSharedPreferences("added_apps", Context.MODE_PRIVATE)
            .getStringSet("apps", emptySet()) ?: return

        for (pkg in packages) {
            HomeworkWidget.scheduleResetIfNeeded(context, pkg)
        }
    }
}

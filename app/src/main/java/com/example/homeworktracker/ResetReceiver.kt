package com.example.homeworktracker
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
class ResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra("target_package") ?: return
        val category = intent.getStringExtra("target_category") ?: "Daily"
        val repo = TaskRepository(context)
        if (category == "Event") {
            ResetScheduler.schedule(context, pkg, category)
            repo.refresh(pkg)
            return
        }
        if (category !in listOf("Daily", "Weekly") || pkg !in repo.packages(category)) {
            ResetScheduler.cancel(context, pkg, category)
            return
        }
        repo.setDone(pkg, category, false)
        ResetScheduler.schedule(context, pkg, category)
    }
}

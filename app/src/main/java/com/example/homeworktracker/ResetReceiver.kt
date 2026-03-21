// ResetReceiver.kt
package com.example.homeworktracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val targetPackage = intent.getStringExtra("target_package") ?: return

        // 완료 상태 초기화
        context.getSharedPreferences("done_status", Context.MODE_PRIVATE)
            .edit().putBoolean(targetPackage, false).apply()

        // 위젯 갱신
        HomeworkWidget.updateAllWidgets(context)
        MiniWidget.updateAllWidgets(context)
        SmallWidget.updateAllWidgets(context)
    }
}
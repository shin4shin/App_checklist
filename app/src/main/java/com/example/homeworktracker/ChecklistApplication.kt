package com.example.homeworktracker

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper

class ChecklistApplication : Application(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sync: SyncManager
    private var running = false
    private var interval = 30_000L
    private var lastState = ""
    private val refresh = object : Runnable {
        override fun run() {
            if (!running && sync.isLoggedIn()) {
                running = true
                sync.syncNowAsync { result ->
                    val state = sync.buildLocalPayload().let { (tasks, settings) ->
                        listOf(tasks, settings).forEach { array ->
                            for (i in 0 until array.length()) array.getJSONObject(i).remove("updatedAt")
                        }
                        tasks.toString() + settings.toString()
                    }
                    interval = if (result.isSuccess && state != lastState) 30_000L else minOf(interval * 2, 300_000L)
                    if (result.isSuccess) lastState = state
                    running = false
                    handler.removeCallbacks(this)
                    handler.postDelayed(this, interval)
                }
                return
            }
            handler.postDelayed(this, interval)
        }
    }
    override fun onCreate() {
        super.onCreate()
        sync = SyncManager(this)
        listOf(TaskRepository.STORE, "personal_plans", "reset_times", "cloud_sync").forEach {
            getSharedPreferences(it, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(this)
        }
        handler.post(refresh)
    }
    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        if (running || key in listOf("last_sync_time", "sent_tasks", "sent_settings")) return
        interval = 30_000L
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 1500)
    }
}

package com.example.homeworktracker

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class GameOverlayService : AccessibilityService() {

    private var overlayView: View? = null
    private var tabView: View? = null
    private var revealOverlayView: View? = null
    private var revealParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private var currentPkg: String? = null
    private var savedY = -1
    private var isMinimized = false
    private var currentAlpha = 0.9f
    private var userDismissed = false  // 사용자가 직접 닫은 경우 재생성 억제
    private var deleteZoneView: View? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pendingHideRunnable: Runnable? = null

    private val overlayWidthPx get() = (220 * resources.displayMetrics.density).toInt()

    private data class Category(val key: String, val displayName: String, val color: Int)
    private val categories = listOf(
        Category("Daily",   "데일리",  Color.parseColor("#5B8DEF")),
        Category("Weekly",  "위클리",  Color.parseColor("#6DB56D")),
        Category("Monthly", "먼슬리",  Color.parseColor("#C878C8")),
        Category("Event",   "이벤트",  Color.parseColor("#E8924A"))
    )

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        savedY = resources.displayMetrics.heightPixels / 3
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val registeredApps = getSharedPreferences("added_apps", MODE_PRIVATE)
            .getStringSet("apps", emptySet()) ?: emptySet()

        if (pkg in registeredApps) {
            cancelPendingHide()
            if (pkg != currentPkg) {
                // 다른 등록 앱으로 전환 → 직접 닫기 상태 초기화 후 새로 표시
                userDismissed = false
                isMinimized = false
                currentPkg = pkg
                showOverlay(pkg)
            } else if (!userDismissed && overlayView == null && tabView == null) {
                // 같은 앱이 다시 포그라운드 + 사용자가 직접 닫지 않은 경우만 재생성
                if (isMinimized) minimizeToTab(pkg) else showOverlay(pkg)
            }
        } else {
            if (currentPkg != null && !isSystemUiPackage(pkg)) {
                // 게임을 나갔으면 직접 닫기 상태 초기화 (다음에 돌아오면 다시 표시)
                userDismissed = false
                schedulePendingHide()
            }
        }
    }

    // ─── 오버레이 표시 ──────────────────────────────────────────────────
    private fun showOverlay(pkg: String) {
        hideAll()
        val view = buildOverlayView(pkg) ?: return

        val topMargin = (resources.displayMetrics.heightPixels * 0.08f).toInt()
        val params = makeOverlayParams().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            gravity = Gravity.LEFT or Gravity.TOP
            x = 0
            y = topMargin
        }

        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                isMinimized = true
                minimizeToTab(pkg)
                true
            } else false
        }

        windowManager?.addView(view, params)
        overlayView = view
    }

    // ─── 오버레이 뷰 생성 (WindowManager 미등록) ────────────────────────
    private fun buildOverlayView(pkg: String): View {
        val tasksByCategory = loadTasksByCategory(pkg)
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_game_tasks, null)

        val tvTitle = view.findViewById<TextView>(R.id.tvOverlayTitle)
        try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            tvTitle.text = packageManager.getApplicationLabel(info)
        } catch (e: Exception) {
            tvTitle.text = pkg
        }

        val container = view.findViewById<LinearLayout>(R.id.taskContainer)
        val dp = resources.displayMetrics.density

        if (tasksByCategory.none { (_, tasks) -> tasks.isNotEmpty() }) {
            val tvEmpty = TextView(this).apply {
                text = "태스크를 추가해 주세요"
                textSize = 12f
                setTextColor(Color.parseColor("#888888"))
                gravity = Gravity.CENTER
                setPadding((12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt())
            }
            container.addView(tvEmpty)
            return view
        }

        var firstSection = true

        for ((categoryKey, displayName, color) in categories) {
            val tasks = tasksByCategory[categoryKey]
            if (tasks.isNullOrEmpty()) continue

            if (!firstSection) {
                val divider = View(this).apply { setBackgroundColor(Color.parseColor("#33FFFFFF")) }
                val divParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(0, (4 * dp).toInt(), 0, (4 * dp).toInt()) }
                container.addView(divider, divParams)
            }
            firstSection = false

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.argb(55, Color.red(color), Color.green(color), Color.blue(color)))
            }
            val accentBar = View(this).apply {
                setBackgroundColor(color)
                layoutParams = LinearLayout.LayoutParams(
                    (4 * dp).toInt(), (28 * dp).toInt()
                )
            }
            val tvCategory = TextView(this).apply {
                text = displayName
                textSize = 11f
                setTextColor(color)
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.06f
                setPadding((10 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
            }
            headerRow.addView(accentBar)
            headerRow.addView(tvCategory)
            container.addView(headerRow)

            tasks.forEachIndexed { index, taskName ->
                val row = inflater.inflate(R.layout.item_overlay_task, container, false)
                val tvCheck = row.findViewById<TextView>(R.id.tvTaskCheck)
                val tvName  = row.findViewById<TextView>(R.id.tvTaskName)
                tvName.text = taskName
                applyCheckStyle(tvCheck, isTaskDone(pkg, categoryKey, index))
                row.setOnClickListener {
                    val nowDone = !isTaskDone(pkg, categoryKey, index)
                    setTaskDone(pkg, categoryKey, index, nowDone)
                    applyCheckStyle(tvCheck, nowDone)
                    if (categoryKey == "Daily") syncWidgetDone(pkg)
                }
                container.addView(row)
            }
        }

        val savedAlphaInt = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getInt("overlay_alpha", 90)
        currentAlpha = savedAlphaInt / 100f
        view.alpha = currentAlpha

        val tvAlphaValue = view.findViewById<TextView>(R.id.tvAlphaValue)
        tvAlphaValue.text = "$savedAlphaInt%"

        view.findViewById<SeekBar>(R.id.seekbarAlpha).apply {
            progress = savedAlphaInt
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    currentAlpha = value.coerceAtLeast(20) / 100f
                    view.alpha = currentAlpha
                    tabView?.alpha = currentAlpha
                    tvAlphaValue.text = "$value%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    getSharedPreferences("app_prefs", MODE_PRIVATE)
                        .edit().putInt("overlay_alpha", (currentAlpha * 100).toInt()).apply()
                }
            })
        }

        view.findViewById<TextView>(R.id.btnMinimizeOverlay).apply {
            contentDescription = "오버레이 최소화"
            setOnClickListener {
                isMinimized = true
                minimizeToTab(pkg)
            }
        }

        // 헤더 롱프레스 → 삭제 존 표시
        view.findViewById<View>(R.id.overlayHeader).setOnLongClickListener {
            showDeleteZone {
                userDismissed = true
                isMinimized = false
                hideAll()
            }
            true
        }

        return view
    }

    // ─── 탭으로 접기 ────────────────────────────────────────────────────
    private fun minimizeToTab(pkg: String) {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { }
            overlayView = null
        }

        val tab = TextView(this).apply {
            text = "▶"
            textSize = 16f
            setTextColor(Color.parseColor("#7EA8FF"))
            gravity = Gravity.CENTER
            val dp = resources.displayMetrics.density
            setPadding((8 * dp).toInt(), (22 * dp).toInt(), (14 * dp).toInt(), (22 * dp).toInt())
            background = ContextCompat.getDrawable(this@GameOverlayService, R.drawable.bg_overlay_tab)
            alpha = currentAlpha
        }

        val tabParams = makeOverlayParams().apply {
            width  = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.LEFT or Gravity.TOP
            flags = flags or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            x = 0
            y = savedY
        }

        var touchStartX = 0f
        var touchStartY = 0f
        var initTabY  = 0
        var dragDir   = 0    // 0=미결정, 1=세로, 2=가로(오버레이 당김)
        var hasDragged = false
        var isLongPressMode = false
        val dp = resources.displayMetrics.density

        val dismissAction = {
            userDismissed = true
            isMinimized = false
            hideAll()
        }
        val longPressRunnable = Runnable {
            isLongPressMode = true
            showDeleteZone(dismissAction)
        }

        tab.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    initTabY   = tabParams.y
                    dragDir    = 0
                    hasDragged = false
                    isLongPressMode = false
                    handler.postDelayed(longPressRunnable, 450)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (isLongPressMode) {
                        // 롱프레스 모드: 탭을 자유롭게 끌 수 있고 삭제 존 하이라이트
                        tabParams.y = (initTabY + dy.toInt()).coerceAtLeast(0)
                        windowManager?.updateViewLayout(tab, tabParams)
                        val inZone = isInDeleteZone(event.rawY)
                        (deleteZoneView as? TextView)?.apply {
                            setTextColor(
                                if (inZone) Color.parseColor("#FF4444")
                                else Color.WHITE
                            )
                            (background as? GradientDrawable)?.setColor(
                                if (inZone) Color.parseColor("#CC880000")
                                else Color.parseColor("#CC333333")
                            )
                        }
                    } else {
                        if (abs(dx) > 10 * dp || abs(dy) > 10 * dp) {
                            handler.removeCallbacks(longPressRunnable)
                        }
                        if (dragDir == 0) {
                            val adx = abs(dx)
                            val ady = abs(dy)
                            val minH = 24 * dp
                            when {
                                adx > ady * 2.5f && adx > minH -> {
                                    hasDragged = true
                                    dragDir = 2
                                    prepareRevealOverlay(pkg)
                                    completeReveal(pkg, tab, tabParams)
                                }
                                ady > adx -> {
                                    hasDragged = ady > 8
                                    if (hasDragged) dragDir = 1
                                }
                            }
                        }
                        if (dragDir == 1) {
                            tabParams.y = (initTabY + dy.toInt()).coerceAtLeast(0)
                            savedY = tabParams.y
                            windowManager?.updateViewLayout(tab, tabParams)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (isLongPressMode) {
                        isLongPressMode = false
                        if (isInDeleteZone(event.rawY)) {
                            hideDeleteZone()
                            dismissAction()
                        } else {
                            // 삭제 존 밖에서 놓으면 탭 위치 저장 후 유지
                            savedY = tabParams.y
                            hideDeleteZone()
                        }
                    } else if (!hasDragged) {
                        expandFromTab(pkg)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    isLongPressMode = false
                    hideDeleteZone()
                    false
                }
                else -> false
            }
        }

        windowManager?.addView(tab, tabParams)
        tabView = tab
    }

    // ─── 드래그 중 오버레이 미리 생성 (translationX로 화면 밖에 숨김) ──
    private fun prepareRevealOverlay(pkg: String) {
        val view = buildOverlayView(pkg)
        val params = makeOverlayParams().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            gravity = Gravity.LEFT or Gravity.TOP
            x = 0
            y = (resources.displayMetrics.heightPixels * 0.08f).toInt()
        }
        view.translationX = -overlayWidthPx.toFloat()  // 화면 왼쪽 밖으로 숨김
        windowManager?.addView(view, params)
        revealOverlayView = view
        revealParams = params
    }

    // ─── 슬라이드인: translationX 0까지, 탭은 오른쪽으로 밀려 사라짐 ──
    private fun completeReveal(pkg: String, tab: View, tabParams: WindowManager.LayoutParams) {
        val overlay = revealOverlayView ?: return

        // 오버레이: translationX -overlayWidthPx → 0
        overlay.animate()
            .translationX(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                isMinimized = false
                overlayView = revealOverlayView
                revealOverlayView = null
                revealParams = null
                overlayView?.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_OUTSIDE) {
                        isMinimized = true
                        minimizeToTab(pkg)
                        true
                    } else false
                }
                try { windowManager?.removeView(tab) } catch (e: Exception) {}
                if (tab === tabView) tabView = null
            }
            .start()

        // 탭: 페이드 아웃 (제거는 overlay withEndAction에서 처리)
        tab.animate()
            .alpha(0f)
            .setDuration(180)
            .start()
    }

    // ─── 탭 탭(단순 클릭) → 오버레이 펼치기 ──────────────────────────
    private fun expandFromTab(pkg: String) {
        isMinimized = false
        tabView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { }
            tabView = null
        }
        showOverlay(pkg)
    }

    private fun schedulePendingHide() {
        cancelPendingHide()
        pendingHideRunnable = Runnable { hideAll() }.also {
            handler.postDelayed(it, 500)
        }
    }

    private fun cancelPendingHide() {
        pendingHideRunnable?.let { handler.removeCallbacks(it) }
        pendingHideRunnable = null
    }

    private fun showDeleteZone(onConfirm: () -> Unit) {
        hideDeleteZone()
        val dp = resources.displayMetrics.density
        val dz = TextView(this).apply {
            text = "× 삭제"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding((28 * dp).toInt(), (14 * dp).toInt(), (28 * dp).toInt(), (14 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 40 * dp
                setColor(Color.parseColor("#CC333333"))
            }
            setOnClickListener {
                hideDeleteZone()
                onConfirm()
            }
        }
        val params = makeOverlayParams().apply {
            width  = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48 * dp).toInt()
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        windowManager?.addView(dz, params)
        deleteZoneView = dz
    }

    private fun hideDeleteZone() {
        deleteZoneView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
        }
        deleteZoneView = null
    }

    private fun isInDeleteZone(rawY: Float): Boolean {
        val screenHeight = resources.displayMetrics.heightPixels
        val dp = resources.displayMetrics.density
        return rawY > screenHeight - (110 * dp).toInt()
    }

    private fun hideAll() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { }
            overlayView = null
        }
        tabView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { }
            tabView = null
        }
        revealOverlayView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { }
            revealOverlayView = null
        }
        hideDeleteZone()
        revealParams = null
    }

    private fun makeOverlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    )

    private fun applyCheckStyle(tvCheck: TextView, isDone: Boolean) {
        tvCheck.text = if (isDone) "✓" else "○"
        tvCheck.setTextColor(if (isDone) Color.parseColor("#66D96E") else Color.parseColor("#666688"))
    }

    override fun onInterrupt() { hideAll() }

    // 사용자가 직접 실행할 수 없는 시스템 컴포넌트(상태바·IME·systemui 등)는 무시
    // 설정·런처처럼 실제로 열 수 있는 시스템 앱은 정상 전환으로 처리
    private fun isSystemUiPackage(pkg: String): Boolean {
        if (pkg == "android" || pkg.contains("systemui", ignoreCase = true)) return true
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            val isSystem = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            isSystem && packageManager.getLaunchIntentForPackage(pkg) == null
        } catch (e: Exception) { false }
    }

    override fun onDestroy() {
        cancelPendingHide()
        hideAll()
        super.onDestroy()
    }

    private fun loadTasksByCategory(pkg: String): Map<String, List<String>> {
        val json = getSharedPreferences("app_tasks", MODE_PRIVATE).getString(pkg, "{}") ?: "{}"
        return try {
            if (json.trim().startsWith("[")) {
                val arr = JSONArray(json)
                val list = (0 until arr.length()).map { arr.getString(it) }
                if (list.isEmpty()) emptyMap() else mapOf("Daily" to list)
            } else {
                val obj = JSONObject(json)
                val result = mutableMapOf<String, List<String>>()
                for (key in obj.keys()) {
                    val arr = obj.getJSONArray(key)
                    result[key] = (0 until arr.length()).map { arr.getString(it) }
                }
                result
            }
        } catch (e: Exception) { emptyMap() }
    }

    private fun syncWidgetDone(pkg: String) {
        val dailyTasks = loadTasksByCategory(pkg)["Daily"] ?: return
        if (dailyTasks.isEmpty()) return
        val allDone = dailyTasks.indices.all { i -> isTaskDone(pkg, "Daily", i) }
        getSharedPreferences("done_status", MODE_PRIVATE)
            .edit().putBoolean(pkg, allDone).apply()
        HomeworkWidget.updateAllWidgets(this)
        MiniWidget.updateAllWidgets(this)
        SmallWidget.updateAllWidgets(this)
    }

    private fun isTaskDone(pkg: String, category: String, index: Int) =
        getSharedPreferences("done_status", MODE_PRIVATE)
            .getBoolean("${pkg}_${category}_$index", false)

    private fun setTaskDone(pkg: String, category: String, index: Int, done: Boolean) {
        getSharedPreferences("done_status", MODE_PRIVATE)
            .edit().putBoolean("${pkg}_${category}_$index", done).apply()
    }
}

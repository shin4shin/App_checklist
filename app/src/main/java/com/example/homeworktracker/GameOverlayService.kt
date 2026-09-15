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

    companion object {
        private var instance: GameOverlayService? = null

        // 이 시간 이상 게임을 떠나 있었으면 새 세션으로 보고 삭제 상태를 해제
        private const val SESSION_GAP_MS = 5 * 60 * 1000L

        // ResetReceiver에서 호출 — 해당 pkg 오버레이가 표시 중이면 즉시 재빌드
        fun refreshIfShowing(pkg: String) {
            val svc = instance ?: return
            if (svc.currentPkg == pkg) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (svc.currentPkg == pkg && !svc.isMinimized && svc.overlayView != null) {
                        svc.showOverlay(pkg)
                    }
                }
            }
        }
    }

    private var overlayView: View? = null
    private var tabView: View? = null
    private var revealOverlayView: View? = null
    private var revealParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private var currentPkg: String? = null
    private var savedY = -1
    private var savedX = 0  // 0 = 왼쪽 엣지
    private var isMinimized = false
    private var currentAlpha = 0.9f
    private var dismissedPkg: String? = null  // 사용자가 직접 삭제한 게임 (이번 세션 동안 재생성 억제)
    private var deleteZoneView: View? = null
    private var headerLongPressRunnable: Runnable? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pendingHideRunnable: Runnable? = null
    private var currentPkgStartTime = 0L  // 현재 게임 포그라운드 진입 시각
    private var lastSystemUiEventTime = 0L  // 마지막 SystemUI 이벤트 시각 (알림바 감지용)
    private var awayStartTime = 0L  // 게임을 떠난 시각 (0 = 떠나지 않음)

    private val overlayWidthPx get() = (220 * resources.displayMetrics.density).toInt()

    private data class Category(val key: String, val displayName: String, val color: Int)
    private val categories = listOf(
        Category("Daily",   "데일리",  Color.parseColor("#2F81F7")),
        Category("Weekly",  "위클리",  Color.parseColor("#3FB950")),
        Category("Monthly", "먼슬리",  Color.parseColor("#A371F7")),
        Category("Event",   "이벤트",  Color.parseColor("#F0883E"))
    )

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        savedY = prefs.getInt("overlay_tab_y", resources.displayMetrics.heightPixels / 3)
        savedX = prefs.getInt("overlay_tab_x", 0)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val registeredApps = getSharedPreferences("added_apps", MODE_PRIVATE)
            .getStringSet("apps", emptySet()) ?: emptySet()

        if (pkg in registeredApps) {
            cancelPendingHide()
            val now = android.os.SystemClock.elapsedRealtime()

            // 삭제했던 게임으로 돌아온 경우: 떠나 있던 시간이 길면 새 세션으로 간주
            if (pkg == dismissedPkg && awayStartTime != 0L && now - awayStartTime > SESSION_GAP_MS) {
                dismissedPkg = null
            }
            awayStartTime = 0L

            if (pkg == dismissedPkg) {
                // 이번 세션 동안은 재생성하지 않음 (진입 시각만 갱신)
                if (pkg != currentPkg) {
                    currentPkg = pkg
                    currentPkgStartTime = now
                }
                return
            }

            if (pkg != currentPkg) {
                isMinimized = false
                currentPkg = pkg
                currentPkgStartTime = now
                showOverlay(pkg)
            } else if (overlayView == null && tabView == null) {
                // 같은 앱이 다시 포그라운드 → 마지막 상태로 재생성
                if (isMinimized) minimizeToTab(pkg) else showOverlay(pkg)
            }
        } else {
            // SystemUI 이벤트(알림바 등) 시각 기록
            if (pkg.contains("systemui", ignoreCase = true)) {
                lastSystemUiEventTime = android.os.SystemClock.elapsedRealtime()
            }
            // 홈 화면으로 나간 경우에만 숨김
            // 예외 1: 게임 진입 직후 2초 이내 → 런처 백그라운드 이벤트 무시
            // 예외 2: 3초 이내에 SystemUI 이벤트가 있었음 → 알림바 조작으로 판단, 무시
            if (currentPkg != null && isHomeLauncherPackage(pkg)) {
                val inGameDuration = android.os.SystemClock.elapsedRealtime() - currentPkgStartTime
                val timeSinceSystemUi = android.os.SystemClock.elapsedRealtime() - lastSystemUiEventTime
                if (inGameDuration > 2000 && timeSinceSystemUi > 3000) {
                    if (awayStartTime == 0L) awayStartTime = android.os.SystemClock.elapsedRealtime()
                    schedulePendingHide()
                }
            }
        }
    }

    // ─── 오버레이 표시 ──────────────────────────────────────────────────
    private fun showOverlay(pkg: String) {
        hideAll()
        val view = buildOverlayView(pkg)

        val topMargin = (resources.displayMetrics.heightPixels * 0.08f).toInt()
        val isPortrait = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
        val leftMargin = if (isPortrait) (16 * resources.displayMetrics.density).toInt() else 0
        val params = makeOverlayParams().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            gravity = Gravity.LEFT or Gravity.TOP
            x = leftMargin
            y = topMargin
        }

        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                isMinimized = true
                minimizeToTab(pkg)
                true
            } else false
        }

        try {
            windowManager?.addView(view, params)
            overlayView = view
            attachHeaderDrag(view, pkg)
        } catch (e: Exception) {
            android.util.Log.e("GameOverlay", "showOverlay addView 실패: ${e.message}")
        }
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
                setTextColor(Color.parseColor("#8B949E"))
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
                val divider = View(this).apply { setBackgroundColor(Color.parseColor("#30363D")) }
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
                    syncWidgetDone(pkg, categoryKey)
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

        return view
    }

    // ─── 탭으로 접기 ────────────────────────────────────────────────────
    private fun minimizeToTab(pkg: String) {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { }
            overlayView = null
        }
        revealOverlayView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { }
            revealOverlayView = null
            revealParams = null
        }
        // 이미 탭이 존재하면 먼저 제거 (중복 방지)
        tabView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { }
            tabView = null
        }

        val screenW = resources.displayMetrics.widthPixels
        val dp = resources.displayMetrics.density
        val isOnLeft = savedX < screenW / 2

        val tab = TextView(this).apply {
            text = if (isOnLeft) "▶" else "◀"
            textSize = 16f
            setTextColor(Color.parseColor("#58A6FF"))
            gravity = Gravity.CENTER
            setPadding(0, (22 * dp).toInt(), (14 * dp).toInt(), (22 * dp).toInt())
            background = ContextCompat.getDrawable(this@GameOverlayService, R.drawable.bg_overlay_tab)
            alpha = currentAlpha
        }

        val screenH = resources.displayMetrics.heightPixels
        val clampedY = savedY.coerceIn(0, screenH - (120 * dp).toInt())

        val tabParams = makeOverlayParams().apply {
            width  = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.LEFT or Gravity.TOP
            flags = flags or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            x = savedX
            y = clampedY
        }

        var touchStartX = 0f
        var touchStartY = 0f
        var initTabX = 0
        var initTabY = 0
        var isDragging = false
        var isSliding = false
        var longPressTriggered = false

        val dismissAction = {
            dismissedPkg = pkg
            isMinimized = false
            hideAll()
        }

        val longPressRunnable = Runnable {
            longPressTriggered = true
            showDeleteZone(dismissAction)
        }

        tab.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    initTabX = tabParams.x
                    initTabY = tabParams.y
                    isDragging = false
                    isSliding = false
                    longPressTriggered = false
                    handler.postDelayed(longPressRunnable, 450)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    val adx = abs(dx)
                    val ady = abs(dy)

                    if (longPressTriggered && !isDragging) {
                        isDragging = true
                    }

                    if (!isDragging && !isSliding && (adx > 20 * dp || ady > 20 * dp)) {
                        handler.removeCallbacks(longPressRunnable)
                        if (adx > ady) {
                            isSliding = true
                            prepareRevealOverlay(pkg)
                        } else {
                            isDragging = true
                            showDeleteZone(dismissAction)
                        }
                    }

                    if (isDragging) {
                        tabParams.x = (initTabX + dx.toInt())
                        tabParams.y = (initTabY + dy.toInt()).coerceAtLeast(0)
                        try { windowManager?.updateViewLayout(tab, tabParams) } catch (e: Exception) {}
                        val inZone = isInDeleteZone(event.rawY)
                        (deleteZoneView as? TextView)?.apply {
                            setTextColor(if (inZone) Color.parseColor("#F85149") else Color.parseColor("#F0F6FC"))
                            (background as? GradientDrawable)?.setColor(
                                if (inZone) Color.parseColor("#CC3B1319") else Color.parseColor("#CC161B22")
                            )
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    when {
                        isDragging -> {
                            isDragging = false
                            if (isInDeleteZone(event.rawY)) {
                                hideDeleteZone()
                                dismissAction()
                            } else {
                                val sw = resources.displayMetrics.widthPixels
                                val snapX = if (tabParams.x < sw / 2) 0 else sw
                                tabParams.x = snapX
                                savedX = snapX
                                savedY = tabParams.y
                                tab.text = if (snapX == 0) "▶" else "◀"
                                getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
                                    .putInt("overlay_tab_x", savedX)
                                    .putInt("overlay_tab_y", savedY)
                                    .apply()
                                try { windowManager?.updateViewLayout(tab, tabParams) } catch (e: Exception) {}
                                hideDeleteZone()
                            }
                        }
                        isSliding -> {
                            isSliding = false
                            completeReveal(pkg, tab, tabParams)
                        }
                        longPressTriggered -> {
                            hideDeleteZone()
                        }
                        else -> {
                            expandFromTab(pkg)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    isDragging = false
                    isSliding = false
                    longPressTriggered = false
                    hideDeleteZone()
                    revealOverlayView?.let {
                        try { windowManager?.removeView(it) } catch (e: Exception) {}
                        revealOverlayView = null
                        revealParams = null
                    }
                    false
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(tab, tabParams)
            tabView = tab
        } catch (e: Exception) {
            android.util.Log.e("GameOverlay", "minimizeToTab addView 실패: ${e.message}")
        }
    }

    // ─── 드래그 중 오버레이 미리 생성 (translationX로 화면 밖에 숨김) ──
    private fun prepareRevealOverlay(pkg: String) {
        revealOverlayView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
            revealOverlayView = null
        }
        val view = buildOverlayView(pkg)
        val isPortrait = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
        val params = makeOverlayParams().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            gravity = Gravity.LEFT or Gravity.TOP
            x = if (isPortrait) (16 * resources.displayMetrics.density).toInt() else 0
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
                overlayView?.let { attachHeaderDrag(it, pkg) }
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
        pendingHideRunnable = Runnable {
            currentPkg = null  // 다음 이벤트에서 재진입으로 인식되도록 초기화
            hideAll()
        }.also {
            handler.postDelayed(it, 5000)
        }
    }

    private fun cancelPendingHide() {
        pendingHideRunnable?.let { handler.removeCallbacks(it) }
        pendingHideRunnable = null
    }

    private fun attachHeaderDrag(view: View, pkg: String) {
        val header = view.findViewById<View>(R.id.overlayHeader) ?: return
        val dp = resources.displayMetrics.density
        val dismissAction = { dismissedPkg = pkg; isMinimized = false; hideAll() }
        val longPressRunnable = Runnable { showDeleteZone(dismissAction) }
        headerLongPressRunnable = longPressRunnable
        var dragMode = false
        var startRawY = 0f

        header.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawY = ev.rawY
                    dragMode = false
                    handler.postDelayed(longPressRunnable, 450)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - startRawY
                    if (!dragMode && dy > 12 * dp) {
                        dragMode = true
                        handler.removeCallbacks(longPressRunnable)
                        showDeleteZone(dismissAction)
                    }
                    if (dragMode) {
                        view.translationY = dy.coerceAtLeast(0f)
                        val inZone = isInDeleteZone(ev.rawY)
                        (deleteZoneView as? TextView)?.apply {
                            setTextColor(if (inZone) Color.parseColor("#F85149") else Color.parseColor("#F0F6FC"))
                            (background as? GradientDrawable)?.setColor(
                                if (inZone) Color.parseColor("#CC3B1319") else Color.parseColor("#CC161B22")
                            )
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (dragMode) {
                        dragMode = false
                        if (isInDeleteZone(ev.rawY)) {
                            hideDeleteZone()
                            dismissAction()
                        } else {
                            hideDeleteZone()
                            view.animate().translationY(0f).setDuration(200).start()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    dragMode = false
                    hideDeleteZone()
                    view.animate().translationY(0f).setDuration(150).start()
                    false
                }
                else -> false
            }
        }
    }

    private fun showDeleteZone(onConfirm: () -> Unit) {
        hideDeleteZone()
        val dp = resources.displayMetrics.density
        val dz = TextView(this).apply {
            text = "× 삭제"
            textSize = 14f
            setTextColor(Color.parseColor("#F0F6FC"))
            gravity = Gravity.CENTER
            setPadding((28 * dp).toInt(), (14 * dp).toInt(), (28 * dp).toInt(), (14 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 40 * dp
                setColor(Color.parseColor("#CC161B22"))
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
        headerLongPressRunnable?.let { handler.removeCallbacks(it) }
        headerLongPressRunnable = null
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
        tvCheck.setTextColor(if (isDone) Color.parseColor("#3FB950") else Color.parseColor("#8B949E"))
    }

    override fun onInterrupt() { hideAll() }

    // 사용자가 직접 실행할 수 없는 시스템 컴포넌트(상태바·IME·systemui 등)는 무시
    // 설정·런처처럼 실제로 열 수 있는 시스템 앱은 정상 전환으로 처리
    private fun isHomeLauncherPackage(pkg: String): Boolean {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == pkg
    }

    // 앱 서랍(CATEGORY_LAUNCHER)에 아이콘이 있는 사용자 앱인지 확인
    // 삼성 게임 툴바·오버레이 등은 런처 아이콘이 없으므로 false
    private fun hasLauncherIcon(pkg: String): Boolean {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            .setPackage(pkg)
        return packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }

    private fun isSystemUiPackage(pkg: String): Boolean {
        if (pkg.contains("systemui", ignoreCase = true)) return true
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

    private fun syncWidgetDone(pkg: String, category: String) {
        val tasks = loadTasksByCategory(pkg)[category] ?: return
        if (tasks.isEmpty()) return
        val allDone = tasks.indices.all { i -> isTaskDone(pkg, category, i) }
        getSharedPreferences("done_status", MODE_PRIVATE)
            .edit().putBoolean(HomeworkWidget.doneKey(pkg, category), allDone).apply()
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

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
                    if (svc.currentPkg != pkg) return@post
                    if (pkg !in TaskRepository(svc).packages()) {
                        svc.hideAll()
                        svc.currentPkg = null
                    } else if (!svc.isMinimized && svc.overlayView != null) {
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
    private var addTaskDialog: androidx.appcompat.app.AlertDialog? = null
    private var isDialogShowing = false  // 마감 설정 창이 떠 있는 동안 바깥 터치로 접히지 않게

    private val overlayWidthPx get() = (220 * resources.displayMetrics.density).toInt()

    private data class Category(val key: String, val displayName: String, val color: Int)
    private val categories = listOf(
        Category("Daily",   "데일리",  Color.parseColor("#2F81F7")),
        Category("Weekly",  "위클리",  Color.parseColor("#3FB950")),
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

    private val windowPackages = mutableMapOf<Int, String>()
    private var monitoringForeground = false
    private val foregroundMonitor = object : Runnable {
        override fun run() {
            if (!monitoringForeground) return
            val power = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (power.isInteractive) foregroundPackage()?.let { handleForegroundPackage(it) }
            handler.postDelayed(this, 1_000L)
        }
    }
    private var lastContentCheck = 0L
    private var transitionPackage: String? = null
    private var transitionUntil = 0L
    private val checkForeground = Runnable {
        foregroundPackage()?.let { handleForegroundPackage(it) }
    }

    private val retryForeground = Runnable {
        foregroundPackage()?.let { handleForegroundPackage(it) }
    }

    private fun scheduleForegroundCheck() {
        handler.removeCallbacks(checkForeground)
        handler.removeCallbacks(retryForeground)
        handler.postDelayed(checkForeground, 150L)
        handler.postDelayed(retryForeground, 650L)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.post(checkForeground)
        monitoringForeground = true
        handler.removeCallbacks(foregroundMonitor)
        handler.post(foregroundMonitor)
    }

    // Only inspect the package on the root; no text or descendant content is read.
    private fun foregroundPackage(): String? {
        val appWindows = windows.filter {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
        }
        val foreground = appWindows.firstOrNull { it.isFocused }
            ?: appWindows.firstOrNull { it.isActive }
        val root = foreground?.root ?: rootInActiveWindow
        val rootMatches = foreground == null || root?.windowId == foreground.id
        val pkg = foreground?.root?.packageName?.toString()
            ?: foreground?.let { windowPackages[it.id] }
            ?: root?.takeIf { rootMatches }?.packageName?.toString()
        // Window snapshots can still describe the previous activity during a transition.
        if (android.os.SystemClock.elapsedRealtime() < transitionUntil && pkg != transitionPackage) return null
        return pkg
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                scheduleForegroundCheck()
                val eventPkg = event.packageName?.toString() ?: return
                val eventWindow = windows.firstOrNull { it.id == event.windowId }
                if (event.windowId >= 0 && (eventWindow == null ||
                            eventWindow.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION)) {
                    windowPackages[event.windowId] = eventPkg
                }
                if (eventWindow != null && (eventWindow.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ||
                            (!eventWindow.isActive && !eventWindow.isFocused))) return
                // Our dialogs, keyboards and system panels are not app switches.
                if (eventPkg == packageName && isDialogShowing) return
                val isApp = eventPkg in TaskRepository(this).packages() || eventPkg == packageName ||
                    isHomeLauncherPackage(eventPkg) || hasLauncherIcon(eventPkg)
                if (!isApp) return
                transitionPackage = eventPkg
                transitionUntil = android.os.SystemClock.elapsedRealtime() + 1_000L
                handleForegroundPackage(eventPkg)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Coalesce window bursts and let the new window's root become available.
                scheduleForegroundCheck()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (event.packageName?.toString() == packageName) return
                val now = android.os.SystemClock.elapsedRealtime()
                if (lastContentCheck != 0L && now - lastContentCheck < 500L) return
                lastContentCheck = now
                foregroundPackage()?.let { handleForegroundPackage(it) }
            }
            else -> return
        }
    }

    private fun handleForegroundPackage(pkg: String) {
        if (pkg == packageName && isDialogShowing) return

        val registeredApps = TaskRepository(this).packages()

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
                isMinimized = true
                currentPkg = pkg
                currentPkgStartTime = now
                minimizeToTab(pkg)
            } else if (overlayView == null && tabView == null) {
                // 같은 앱이 다시 포그라운드 → 마지막 상태로 재생성
                if (isMinimized) minimizeToTab(pkg) else showOverlay(pkg)
            }
        } else {
            if (isSystemUiPackage(pkg) && pkg != packageName) return
            if (pkg != packageName && !isHomeLauncherPackage(pkg) && !hasLauncherIcon(pkg)) return
            if (currentPkg != null && awayStartTime == 0L) awayStartTime = android.os.SystemClock.elapsedRealtime()
            cancelPendingHide()
            hideAll()
            currentPkg = null
        }
    }

    // ─── 오버레이 표시 ──────────────────────────────────────────────────
    private fun showOverlay(pkg: String) {
        hideAll()
        val view = buildOverlayView(pkg)

        val verticalMargin = constrainOverlayHeight(view)
        val isPortrait = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
        val leftMargin = if (isPortrait) (16 * resources.displayMetrics.density).toInt() else 0
        val params = makeOverlayParams().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            gravity = Gravity.LEFT or Gravity.TOP
            x = leftMargin
            y = verticalMargin
        }

        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE && !isDialogShowing) {
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

    // 화면 위아래에 같은 여백을 남기고, 내용이 넘치면 목록만 스크롤되도록 높이를 제한한다.
    // 반환값은 창의 y 위치로 쓰는 위아래 여백.
    private fun constrainOverlayHeight(view: View): Int {
        val screenH = resources.displayMetrics.heightPixels
        val verticalMargin = (screenH * 0.08f).toInt()
        val availableH = screenH - verticalMargin * 2
        val scroll = view.findViewById<android.widget.ScrollView>(R.id.taskScroll)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
        if (view.measuredHeight > availableH) {
            // 헤더·구분선·슬라이더가 쓰는 높이를 뺀 나머지를 목록에 배정
            scroll.layoutParams.height = availableH - (view.measuredHeight - scroll.measuredHeight)
        }
        return verticalMargin
    }

    // ─── 오버레이 뷰 생성 (WindowManager 미등록) ────────────────────────
    private fun buildOverlayView(pkg: String): View {
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

        val repository = TaskRepository(this)
        for (category in categories) {
            val tasks = repository.tasks(pkg, category.key)
            val heading = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.argb(55, Color.red(category.color), Color.green(category.color), Color.blue(category.color)))
            }
            heading.addView(TextView(this).apply {
                text = category.displayName
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(category.color)
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            heading.addView(TextView(this).apply {
                text = "+"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(category.color)
                contentDescription = "${category.displayName} 항목 추가"
                isFocusable = true
                setBackgroundResource(R.drawable.ripple_overlay_task)
                setOnClickListener { showAddTaskDialog(pkg, category) }
            }, LinearLayout.LayoutParams((48 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT))
            container.addView(heading)
            for (task in tasks) {
                val row = inflater.inflate(R.layout.item_overlay_task, container, false)
                val check = row.findViewById<TextView>(R.id.tvTaskCheck)
                row.findViewById<TextView>(R.id.tvTaskName).text = if (category.key == "Event") "${task.title}\n${EventDeadline.label(task.deadline)}" else task.title
                if (category.key == "Event") {
                    row.findViewById<TextView>(R.id.tvTaskName).maxLines = 3
                    row.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    row.setPadding(row.paddingLeft, (8 * dp).toInt(), row.paddingRight, (8 * dp).toInt())
                }
                row.minimumHeight = (48 * dp).toInt()
                row.isFocusable = true
                row.contentDescription = "${task.title}, ${if (task.done) "완료" else "미완료"}" +
                    if (category.key == "Event") ", ${EventDeadline.label(task.deadline)}" else ""
                applyCheckStyle(check, task.done)
                row.setOnClickListener {
                    repository.setTaskDone(pkg, category.key, task.id, !task.done)
                }
                if (category.key == "Event") {
                    row.findViewById<TextView>(R.id.btnOverlayDeadline).apply {
                        visibility = View.VISIBLE
                        contentDescription = "${task.title} 시간 설정"
                        setOnClickListener { showEventDeadlineDialog(pkg, task) }
                    }
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

    private fun showAddTaskDialog(pkg: String, category: Category) {
        if (isDialogShowing) return
        val themed = android.view.ContextThemeWrapper(this, R.style.Theme_HomeworkTracker)
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(themed)
        val input = android.widget.EditText(builder.context).apply {
            hint = "할 일 입력"
            contentDescription = "새 ${category.displayName} 항목"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = android.widget.FrameLayout(builder.context).apply {
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        val dialog = builder.setTitle("${category.displayName} 항목 추가").setView(content)
            .setNegativeButton("취소", null).setPositiveButton("추가", null).create()
        fun submit() {
            val title = input.text.toString().trim()
            if (title.isEmpty()) { input.error = "할 일을 입력해 주세요"; return }
            TaskRepository(this).addTask(pkg, category.key, title)
            dialog.dismiss()
        }
        dialog.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        dialog.setOnDismissListener { isDialogShowing = false; addTaskDialog = null }
        isDialogShowing = true
        addTaskDialog = dialog
        dialog.show()
    }

    // ─── 이벤트 마감 설정 (오버레이 위에서 바로) ────────────────────────
    private fun showEventDeadlineDialog(pkg: String, task: TaskItem) {
        val themed = android.view.ContextThemeWrapper(this, R.style.Theme_HomeworkTracker)
        val repository = TaskRepository(this)
        isDialogShowing = true
        val dialog = EventDeadlineDialog.show(
            themed,
            task.title,
            task.deadline,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        ) { duration ->
            val deadline = duration?.let { EventDeadline.calculate(System.currentTimeMillis(), it) } ?: 0L
            repository.saveTasks(pkg, "Event", repository.tasks(pkg, "Event").map {
                if (it.id == task.id) it.copy(deadline = deadline) else it
            })
        }
        dialog.setOnDismissListener { isDialogShowing = false }
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
        val clampedY = savedY.coerceIn(0, (screenH - (120 * dp).toInt()).coerceAtLeast(0))

        val tabParams = makeOverlayParams().apply {
            width  = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.LEFT or Gravity.TOP
            flags = flags or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            x = if (isOnLeft) 0 else (screenW - (48 * dp).toInt()).coerceAtLeast(0)
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
        val verticalMargin = constrainOverlayHeight(view)
        val isPortrait = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
        val params = makeOverlayParams().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            gravity = Gravity.LEFT or Gravity.TOP
            x = if (isPortrait) (16 * resources.displayMetrics.density).toInt() else 0
            y = verticalMargin
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
                    if (event.action == MotionEvent.ACTION_OUTSIDE && !isDialogShowing) {
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
            pendingHideRunnable = null
            val foreground = foregroundPackage()
            if (foreground != null && foreground in TaskRepository(this).packages()) {
                handleForegroundPackage(foreground)
            } else if (foreground != null && isHomeLauncherPackage(foreground)) {
                currentPkg = null
                hideAll()
            }
            // A stale launcher event or an unavailable root must not hide a returning game's tab.
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
        monitoringForeground = false
        handler.removeCallbacks(foregroundMonitor)
        windowPackages.clear()
        addTaskDialog?.dismiss()
        handler.removeCallbacks(checkForeground)
        handler.removeCallbacks(retryForeground)
        cancelPendingHide()
        hideAll()
        if (instance === this) instance = null
        super.onDestroy()
    }

}

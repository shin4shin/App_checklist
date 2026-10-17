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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
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
    private var tabLongPressRunnable: Runnable? = null
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val screenBounds: android.graphics.Rect
        get() {
            if (Build.VERSION.SDK_INT >= 30) return windowManager!!.currentWindowMetrics.bounds
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager!!.defaultDisplay.getRealMetrics(metrics)
            return android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    private val handleWidth get() = dp(48) * 2 / 3
    private val handleHeight get() = dp(88) * 2 / 3
    private val isOnLeft get() = savedX == 0
    private val panelWidth get() = minOf(dp(320), (screenBounds.width() * 0.85f).toInt() - handleWidth).coerceAtLeast(1)
    private fun handleY() = savedY.coerceIn(0, (screenBounds.height() - handleHeight).coerceAtLeast(0))
    private fun handleX() = if (isOnLeft) 0 else (screenBounds.width() - handleWidth).coerceAtLeast(0)

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
        savedY = prefs.getInt("overlay_tab_y", screenBounds.height() / 3)
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

    // The panel and its handle share a drawer so they move as one surface.
    private fun buildDrawer(pkg: String): FrameLayout {
        val root = FrameLayout(this)
        val scrim = View(this).apply {
            tag = "scrim"
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener { if (!isDialogShowing) collapseDrawer(pkg) }
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val drawer = FrameLayout(this).apply { tag = "drawer" }
        root.addView(drawer, FrameLayout.LayoutParams(panelWidth + handleWidth, -1,
            Gravity.TOP or if (isOnLeft) Gravity.LEFT else Gravity.RIGHT))
        val panel = buildOverlayView(pkg)
        panel.setBackgroundColor(Color.parseColor("#161B22"))
        panel.isClickable = true
        panel.findViewById<android.widget.ScrollView>(R.id.taskScroll).layoutParams =
            LinearLayout.LayoutParams(-1, 0, 1f)
        drawer.addView(panel, FrameLayout.LayoutParams(panelWidth, -1,
            Gravity.TOP or if (isOnLeft) Gravity.LEFT else Gravity.RIGHT))
        attachHeaderDrag(panel, pkg)
        val handle = buildHandle().apply {
            tag = "drawer_handle"
            contentDescription = "오버레이 접기"
            setOnClickListener { collapseDrawer(pkg) }
        }
        drawer.addView(handle, FrameLayout.LayoutParams(handleWidth, handleHeight,
            Gravity.TOP or if (isOnLeft) Gravity.RIGHT else Gravity.LEFT).apply { topMargin = handleY() })
        var startX = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; true }
                MotionEvent.ACTION_MOVE -> {
                    val distance = (event.rawX - startX) * if (isOnLeft) -1 else 1
                    setReveal(root, 1f - distance.coerceAtLeast(0f) / panelWidth)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - startX) < dp(8)) handle.performClick()
                    else if (abs(drawer.translationX) > panelWidth * 0.25f) collapseDrawer(pkg)
                    else animateReveal(root, 1f)
                    true
                }
                MotionEvent.ACTION_CANCEL -> { animateReveal(root, 1f); true }
                else -> false
            }
        }
        return root
    }

    private fun buildHandle(): FrameLayout = FrameLayout(this).apply {
        contentDescription = "오버레이 펼치기, 위아래로 끌어 위치 이동"
        isFocusable = true
        alpha = currentAlpha
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#E6161B22"))
            val radius = dp(24) * 2 / 3f
            cornerRadii = if (isOnLeft) floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
                else floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
        }
        addView(View(this@GameOverlayService).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6EDF3")); cornerRadius = dp(3) * 2 / 3f
            }
        }, FrameLayout.LayoutParams((dp(5) * 2 / 3).coerceAtLeast(1), dp(32) * 2 / 3, Gravity.CENTER))
    }

    private fun drawerParams() = edgeOverlayParams().apply {
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        gravity = Gravity.TOP or Gravity.LEFT
        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

    private fun setReveal(root: View, progress: Float) {
        val fraction = progress.coerceIn(0f, 1f)
        root.findViewWithTag<View>("drawer").translationX = (1f - fraction) * panelWidth * if (isOnLeft) -1 else 1
        root.findViewWithTag<View>("scrim").alpha = fraction
    }

    private fun animateReveal(root: View, progress: Float, finished: () -> Unit = {}) {
        root.findViewWithTag<View>("drawer").animate()
            .translationX((1f - progress) * panelWidth * if (isOnLeft) -1 else 1)
            .setDuration(220).setInterpolator(DecelerateInterpolator()).withEndAction(finished).start()
        root.findViewWithTag<View>("scrim").animate().alpha(progress).setDuration(220).start()
    }

    private fun collapseDrawer(pkg: String) {
        val root = overlayView ?: return
        animateReveal(root, 0f) {
            if (overlayView === root) { isMinimized = true; minimizeToTab(pkg) }
        }
    }

    private fun showOverlay(pkg: String) {
        hideAll()
        val view = buildDrawer(pkg)
        try {
            windowManager?.addView(view, drawerParams())
            overlayView = view
        } catch (e: Exception) {
            android.util.Log.e("GameOverlay", "showOverlay addView 실패: ${e.message}")
        }
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
                    overlayView?.findViewWithTag<View>("drawer_handle")?.alpha = currentAlpha
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
            text = if (isOnLeft) "◀" else "▶"
            contentDescription = "오버레이 최소화"
            setOnClickListener {
                collapseDrawer(pkg)
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

    // Saved coordinates change only after an intentional handle move.
    private fun minimizeToTab(pkg: String) {
        hideAll()
        currentAlpha = getSharedPreferences("app_prefs", MODE_PRIVATE).getInt("overlay_alpha", 90) / 100f
        val tab = buildHandle()
        val params = edgeOverlayParams().apply {
            width = handleWidth; height = handleHeight
            gravity = Gravity.TOP or Gravity.LEFT
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            x = handleX(); y = handleY()
        }
        var startX = 0f
        var startY = 0f
        var initialX = 0
        var initialY = 0
        var dragging = false
        var sliding = false
        var longPressed = false
        var reveal = 0f
        val dismiss = { dismissedPkg = pkg; isMinimized = false; hideAll() }
        val longPress = Runnable { longPressed = true; showDeleteZone(dismiss) }
        tabLongPressRunnable = longPress
        tab.setOnClickListener { expandFromTab(pkg) }
        tab.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    initialX = params.x; initialY = params.y
                    dragging = false; sliding = false; longPressed = false; reveal = 0f
                    handler.postDelayed(longPress, 450)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!dragging && !sliding && (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                        handler.removeCallbacks(longPress)
                        if (!longPressed && abs(dx) > abs(dy)) {
                            sliding = true
                            prepareRevealOverlay(pkg)
                            tab.alpha = 0f
                        } else {
                            dragging = true
                            showDeleteZone(dismiss)
                        }
                    }
                    if (sliding) {
                        reveal = ((if (isOnLeft) dx else -dx) / panelWidth).coerceIn(0f, 1f)
                        revealOverlayView?.let { setReveal(it, reveal) }
                    }
                    if (dragging) {
                        params.x = (initialX + dx.toInt()).coerceIn(0, (screenBounds.width() - handleWidth).coerceAtLeast(0))
                        params.y = (initialY + dy.toInt()).coerceIn(0, (screenBounds.height() - handleHeight).coerceAtLeast(0))
                        windowManager?.updateViewLayout(tab, params)
                        (deleteZoneView as? TextView)?.setTextColor(
                            Color.parseColor(if (isInDeleteZone(event.rawY)) "#F85149" else "#F0F6FC"))
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    when {
                        dragging -> {
                            if (isInDeleteZone(event.rawY)) dismiss()
                            else {
                                savedX = if (params.x + handleWidth / 2 < screenBounds.width() / 2) 0 else screenBounds.width()
                                savedY = params.y
                                getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
                                    .putInt("overlay_tab_x", savedX).putInt("overlay_tab_y", savedY).apply()
                                minimizeToTab(pkg)
                            }
                        }
                        sliding -> {
                            if (reveal >= 0.25f) completeReveal()
                            else minimizeToTab(pkg)
                        }
                        longPressed -> hideDeleteZone()
                        else -> tab.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    minimizeToTab(pkg)
                    true
                }
                else -> false
            }
        }
        try {
            windowManager?.addView(tab, params)
            tabView = tab
        } catch (e: Exception) {
            android.util.Log.e("GameOverlay", "minimizeToTab addView 실패: ${e.message}")
        }
    }

    private fun prepareRevealOverlay(pkg: String) {
        val root = buildDrawer(pkg)
        setReveal(root, 0f)
        windowManager?.addView(root, drawerParams())
        revealOverlayView = root
    }

    private fun completeReveal() {
        val root = revealOverlayView ?: return
        overlayView = root
        revealOverlayView = null
        isMinimized = false
        tabView?.let { windowManager?.removeView(it) }
        tabView = null
        animateReveal(root, 1f)
    }

    private fun expandFromTab(pkg: String) {
        prepareRevealOverlay(pkg)
        completeReveal()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val pkg = currentPkg ?: return
        if (pkg == dismissedPkg) return
        if (isMinimized) minimizeToTab(pkg) else showOverlay(pkg)
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
        val screenHeight = screenBounds.height()
        val dp = resources.displayMetrics.density
        return rawY > screenHeight - (110 * dp).toInt()
    }

    private fun hideAll() {
        tabLongPressRunnable?.let { handler.removeCallbacks(it) }
        tabLongPressRunnable = null
        for (root in listOfNotNull(overlayView, revealOverlayView)) {
            root.findViewWithTag<View>("drawer")?.animate()?.cancel()
            root.findViewWithTag<View>("scrim")?.animate()?.cancel()
        }
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
    }

    // Use physical screen edges, including the camera cutout and system bar areas.
    private fun edgeOverlayParams() = makeOverlayParams().apply {
        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (Build.VERSION.SDK_INT >= 30) {
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
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

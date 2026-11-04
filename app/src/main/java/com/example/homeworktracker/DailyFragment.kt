package com.example.homeworktracker

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class DailyFragment : Fragment() {

    companion object {
        private const val ARG_CATEGORY = "category"
        fun newInstance(category: String) = DailyFragment().apply {
            arguments = Bundle().apply { putString(ARG_CATEGORY, category) }
        }
    }

    private val category: String get() = arguments?.getString(ARG_CATEGORY) ?: "Daily"

    private lateinit var adapter: AppAdapter
    private val appList = mutableListOf<AppInfo>()
    private val deadlineHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val deadlineRefresh = object : Runnable {
        override fun run() {
            if (isResumed && ::adapter.isInitialized) {
                adapter.notifyDataSetChanged()
                deadlineHandler.postDelayed(this, 30_000)
            }
        }
    }
    private val stateListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (isResumed && ::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.activity_main, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.title = when (category) { "Weekly" -> "위클리 설정"; "Event" -> "이벤트 설정"; else -> "데일리 설정" }
        toolbar.setLogo(when (category) { "Weekly" -> R.drawable.ic_nav_weekly; "Event" -> R.drawable.ic_nav_monthly; else -> R.drawable.ic_nav_daily })
        toolbar.logoDescription = toolbar.title
        setupRecyclerView(view)
        setupFab(view)
        setupMenu(view)
        setupSelectionBar(view)
        setupSaveButton(view)
        setupBackPress()
    }

    override fun onResume() {
        super.onResume()
        if (category == "Event") deadlineHandler.postDelayed(deadlineRefresh, 30_000)
        requireContext().getSharedPreferences(TaskRepository.STORE, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(stateListener)
        if (::adapter.isInitialized) refreshAppList()
    }

    override fun onPause() {
        deadlineHandler.removeCallbacks(deadlineRefresh)
        requireContext().getSharedPreferences(TaskRepository.STORE, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(stateListener)
        persistDraft()
        super.onPause()
    }

    fun persistDraft() {
        if (::adapter.isInitialized) adapter.persistDraft()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::adapter.isInitialized) adapter.persistDraft()
        super.onSaveInstanceState(outState)
    }

    private fun setupSaveButton(view: View) {
        fun updateSaveVisibility() {
            view.findViewById<Button>(R.id.btnSave).visibility =
                if (adapter.hasChanges() && !adapter.isSelectionMode) View.VISIBLE else View.GONE
        }
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = updateSaveVisibility()
        })
        updateSaveVisibility()
        view.findViewById<Button>(R.id.btnSave).setOnClickListener {
            if (adapter.hasChanges()) saveAll()
            else Toast.makeText(requireContext(), "변경된 내용이 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBackPress() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (adapter.isSelectionMode) {
                        adapter.exitSelectionMode()
                        view?.findViewById<LinearLayout>(R.id.layoutSelectionBar)?.visibility = View.GONE
                        return
                    }
                    if (adapter.hasChanges()) {
                        AlertDialog.Builder(requireContext())
                            .setMessage("저장하지 않은 변경 사항이 있습니다.\n저장하시겠습니까?")
                            .setPositiveButton("네") { _, _ ->
                                saveAll()
                                navigateToHome()
                            }
                            .setNegativeButton("아니오") { _, _ ->
                                adapter.discardChanges()
                                navigateToHome()
                            }
                            .show()
                    } else {
                        navigateToHome()
                    }
                }
            })
    }

    private fun navigateToHome() {
        requireActivity().findViewById<BottomNavigationView>(R.id.bottomNav)
            ?.selectedItemId = R.id.nav_home
    }

    private fun saveAll() {
        adapter.commitChanges()
        val ctx = requireContext()
        HomeworkWidget.updateAllWidgets(ctx)
        MiniWidget.updateAllWidgets(ctx)
        SmallWidget.updateAllWidgets(ctx)
        Toast.makeText(ctx, "저장되었습니다", Toast.LENGTH_SHORT).show()
    }

    private fun showAppPickerDialog() {
        val ctx = requireContext()
        val addedPackages = getAddedAppPackages()
        val installedApps = getAllInstalledApps().filter { it.packageName !in addedPackages }
        if (installedApps.isEmpty()) {
            Toast.makeText(ctx, "추가할 앱이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearch)
        val rvApps = dialogView.findViewById<RecyclerView>(R.id.rvPickerApps)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("앱 추가")
            .setView(dialogView)
            .setNegativeButton("취소", null)
            .create()
        val pickerAdapter = AppPickerAdapter(ctx, installedApps.toMutableList()) { app ->
            saveAddedApp(app.packageName)
            refreshAppList()
            HomeworkWidget.updateAllWidgets(ctx)
            dialog.dismiss()
        }
        rvApps.layoutManager = LinearLayoutManager(ctx)
        rvApps.adapter = pickerAdapter
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { pickerAdapter.filter(s.toString()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        dialog.show()
    }

    private fun removeApp(app: AppInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle("앱 삭제")
            .setMessage("${app.name}을(를) 목록에서 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                deleteAddedApp(app.packageName)
                refreshAppList()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun setupMenu(view: View) {
        view.findViewById<TextView>(R.id.tvMenu).setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            popup.menu.add(0, 1, 0, "정렬")
            popup.menu.add(0, 2, 1, "선택")
            popup.menu.add(0, 3, 2, "오버레이 설정")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> showSortDialog()
                    2 -> adapter.enterSelectionMode()
                    3 -> showOverlaySetupDialog()
                }
                true
            }
            popup.show()
        }
    }

    private fun showSortDialog() {
        val ctx = requireContext()
        val options = arrayOf("가나다 순", "시간 순")
        AlertDialog.Builder(ctx)
            .setTitle("정렬")
            .setSingleChoiceItems(options, adapter.sortMode) { dialog, which ->
                adapter.setSortMode(which)
                HomeworkWidget.updateAllWidgets(ctx)
                MiniWidget.updateAllWidgets(ctx)
                SmallWidget.updateAllWidgets(ctx)
                dialog.dismiss()
            }
            .show()
    }

    private fun setupSelectionBar(view: View) {
        val bar = view.findViewById<LinearLayout>(R.id.layoutSelectionBar)
        view.findViewById<Button>(R.id.btnSelectionDelete).setOnClickListener {
            val selected = adapter.selectedPackages.toList()
            if (selected.isEmpty()) { Toast.makeText(requireContext(), "선택된 앱이 없습니다", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            AlertDialog.Builder(requireContext())
                .setMessage("선택한 앱 ${selected.size}개를 삭제할까요?")
                .setPositiveButton("삭제") { _, _ ->
                    for (pkg in selected) { deleteAddedApp(pkg) }
                    adapter.exitSelectionMode()
                    bar.visibility = View.GONE
                    refreshAppList()
                }
                .setNegativeButton("취소", null)
                .show()
        }
        view.findViewById<Button>(R.id.btnSelectionSetTime).text = "시간 설정"
        view.findViewById<Button>(R.id.btnSelectionSetTime).setOnClickListener {
            if (adapter.selectedPackages.isEmpty()) { Toast.makeText(requireContext(), "선택된 앱이 없습니다", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            adapter.showBulkTimePicker()
        }
        view.findViewById<Button>(R.id.btnSelectionCancel).setOnClickListener {
            adapter.exitSelectionMode()
            bar.visibility = View.GONE
        }
    }

    private fun setupRecyclerView(view: View) {
        val ctx = requireContext()
        val recyclerView = view.findViewById<RecyclerView>(R.id.rvApps)
        val bar = view.findViewById<LinearLayout>(R.id.layoutSelectionBar)
        val btnSave = view.findViewById<Button>(R.id.btnSave)
        adapter = AppAdapter(
            context = ctx,
            appList = appList,
            onRemove = { app -> removeApp(app) },
            onSave = { saveAll() },
            onSelectionChanged = { isSelecting ->
                bar.visibility = if (isSelecting) View.VISIBLE else View.GONE
                btnSave.visibility = if (!isSelecting && adapter.hasChanges()) View.VISIBLE else View.GONE
                view.findViewById<FloatingActionButton>(R.id.fabAddApp)
                    .visibility = if (isSelecting) View.GONE else View.VISIBLE
            },
            onTaskClick = { app -> showTaskDialog(app) },
            category = category
        )
        recyclerView.layoutManager = LinearLayoutManager(ctx)
        recyclerView.adapter = adapter
        refreshAppList()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFab(view: View) {
        val fab = view.findViewById<FloatingActionButton>(R.id.fabAddApp)
        var dX = 0f; var dY = 0f
        var startRawX = 0f; var startRawY = 0f
        var isDragging = false
        fab.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX; dY = v.y - event.rawY
                    startRawX = event.rawX; startRawY = event.rawY; isDragging = false; false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(event.rawX - startRawX) > 10 || Math.abs(event.rawY - startRawY) > 10) isDragging = true
                    if (isDragging) {
                        val newX = (event.rawX + dX).coerceIn(0f, (resources.displayMetrics.widthPixels - v.width).toFloat())
                        val newY = (event.rawY + dY).coerceIn(0f, (resources.displayMetrics.heightPixels - v.height).toFloat())
                        v.animate().x(newX).y(newY).setDuration(0).start()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!isDragging) showAppPickerDialog(); true }
                else -> false
            }
        }
    }

    private fun saveAddedApp(pkg: String) = TaskRepository(requireContext()).addApp(pkg, category)

    private fun deleteAddedApp(pkg: String) {
        adapter.pendingChanges.remove(pkg)
        adapter.pendingDays.remove(pkg)
        adapter.persistDraft()
        TaskRepository(requireContext()).removeApp(pkg, category)
    }

    fun getAddedAppPackages(): Set<String> = TaskRepository(requireContext()).packages(category)

    private fun getAllInstalledApps(): List<AppInfo> {
        val pm = requireContext().packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .filter { it.packageName != requireContext().packageName }
            .map { AppInfo(pm.getApplicationLabel(it).toString(), it.packageName, pm.getApplicationIcon(it)) }
            .sortedBy { it.name }
    }

    private fun refreshAppList() {
        val pm = requireContext().packageManager
        val packages = getAddedAppPackages()
        appList.clear()
        appList.addAll(packages.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                AppInfo(pm.getApplicationLabel(info).toString(), pkg, pm.getApplicationIcon(info))
            } catch (e: Exception) { null }
        }.sortedBy { it.name })
        adapter.refreshSort()
        adapter.notifyDataSetChanged()
    }

    private fun showTaskDialog(app: AppInfo) {
        val ctx = requireContext()
        val dialogView = layoutInflater.inflate(R.layout.dialog_task_list, null)
        dialogView.findViewById<Spinner>(R.id.spinnerCategory).visibility = View.GONE
        val etNewTask = dialogView.findViewById<EditText>(R.id.etNewTask)
        val btnAdd    = dialogView.findViewById<Button>(R.id.btnAddTask)
        val rvTasks   = dialogView.findViewById<RecyclerView>(R.id.rvTasks)
        val repository = TaskRepository(ctx)
        val currentTasks = repository.tasks(app.packageName, category).toMutableList()
        val resetPrefs = ctx.getSharedPreferences("event_common_reset", Context.MODE_PRIVATE)
        var commonMinute = resetPrefs.getInt(app.packageName, -1)
        val selectedTasks = mutableSetOf<String>()
        var selecting = false
        val commonTime = dialogView.findViewById<Button>(R.id.eventCommonTime)
        fun updateSelection() {
            commonTime.text = if (commonMinute < 0) "공통 초기화 시간" else "공통 초기화 시간 ${EventResetTime.label(commonMinute)}"
        }
        val taskAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = currentTasks.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(layoutInflater.inflate(R.layout.item_task_edit, parent, false)) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val task = currentTasks[position]
                holder.itemView.findViewById<android.widget.CheckBox>(R.id.eventTaskSelected).apply {
                    visibility = if (category == "Event" && selecting) View.VISIBLE else View.GONE
                    isChecked = task.id in selectedTasks
                }
                holder.itemView.findViewById<TextView>(R.id.tvTaskItem).text = task.title
                holder.itemView.findViewById<TextView>(R.id.tvTaskDeadline).apply {
                    visibility = if (category == "Event") View.VISIBLE else View.GONE
                    text = EventDeadline.label(task.deadline) + if (task.resetMinute >= 0) " · ${EventResetTime.label(task.resetMinute)} 고정" else ""
                }
                if (category == "Event") holder.itemView.apply {
                    isClickable = true
                    isFocusable = true
                    contentDescription = if (selecting) "${task.title} 선택" else "${task.title} 시간 설정"
                    setOnLongClickListener {
                        selecting = true
                        selectedTasks.add(task.id)
                        updateSelection()
                        notifyDataSetChanged()
                        true
                    }
                    setOnClickListener {
                        if (selecting) {
                            if (!selectedTasks.add(task.id)) selectedTasks.remove(task.id)
                            updateSelection()
                            notifyItemChanged(position)
                            return@setOnClickListener
                        }
                        EventDeadlineDialog.show(ctx, "${app.name} · ${task.title}", task.deadline,
                            onRoutine = { deadline, period ->
                                val index = currentTasks.indexOfFirst { it.id == task.id }
                                if (index >= 0) {
                                    currentTasks[index] = EventResetTime.apply(currentTasks[index].copy(deadline = deadline, routineDays = period))
                                    notifyItemChanged(index)
                                }
                            }) { duration ->
                            val index = currentTasks.indexOfFirst { it.id == task.id }
                            if (index >= 0) {
                                currentTasks[index] = EventResetTime.apply(currentTasks[index].copy(deadline = duration?.let {
                                    EventDeadline.calculate(System.currentTimeMillis(), it)
                                } ?: 0L, routineDays = if (currentTasks[index].resetMinute >= 0) currentTasks[index].routineDays else 0L))
                                notifyItemChanged(index)
                            }
                        }
                    }
                }
                holder.itemView.findViewById<TextView>(R.id.btnDeleteTask).setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) { selectedTasks.remove(currentTasks[pos].id); currentTasks.removeAt(pos); updateSelection(); notifyItemRemoved(pos) }
                }
            }
        }
        if (category == "Event") {
            dialogView.findViewById<View>(R.id.eventResetControls).visibility = View.VISIBLE
            updateSelection()
            commonTime.setOnClickListener {
                val initial = commonMinute.takeIf { it >= 0 } ?: 0
                val picker = layoutInflater.inflate(R.layout.dialog_common_reset_time, null) as android.widget.TimePicker
                picker.setIs24HourView(true)
                picker.hour = initial / 60
                picker.minute = initial % 60
                AlertDialog.Builder(ctx).setTitle("공통 초기화 시간").setView(picker)
                    .setNegativeButton("취소", null)
                    .setPositiveButton("설정") { _, _ ->
                    picker.clearFocus()
                    commonMinute = picker.hour * 60 + picker.minute
                    currentTasks.indices.forEach { index ->
                        if (currentTasks[index].resetMinute >= 0) currentTasks[index] = EventResetTime.apply(currentTasks[index].copy(resetMinute = commonMinute))
                    }
                    updateSelection()
                    taskAdapter.notifyDataSetChanged()
                }.show()
            }

        }
        rvTasks.layoutManager = LinearLayoutManager(ctx)
        rvTasks.adapter = taskAdapter
        btnAdd.setOnClickListener {
            val text = etNewTask.text.toString().trim()
            if (text.isNotEmpty()) { currentTasks.add(TaskItem(title = text)); taskAdapter.notifyItemInserted(currentTasks.size - 1); etNewTask.text.clear() }
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("${app.name} - 할 일 목록")
            .setView(dialogView)
            .setPositiveButton("저장", null)
            .setNeutralButton(if (category == "Event") "가져오기" else if (repository.isDone(app.packageName, category)) "완료 취소" else "전체 완료") { _, _ ->
                val done = !repository.isDone(app.packageName, category)
                repository.saveTasks(app.packageName, category, currentTasks)
                repository.setDone(app.packageName, category, done)
            }
            .setNegativeButton("취소", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val wasSelecting = selecting
            if (wasSelecting) {
                if (selectedTasks.isNotEmpty() && commonMinute < 0) {
                    Toast.makeText(ctx, "공통 초기화 시간을 먼저 설정해 주세요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                currentTasks.indices.forEach { index ->
                    if (currentTasks[index].id in selectedTasks) {
                        currentTasks[index] = EventResetTime.apply(currentTasks[index].copy(resetMinute = commonMinute))
                    }
                }
            }
            if (category == "Event") resetPrefs.edit().putInt(app.packageName, commonMinute).apply()
            repository.saveTasks(app.packageName, category, currentTasks)
            adapter.notifyDataSetChanged()
            if (wasSelecting) {
                selecting = false
                selectedTasks.clear()
                currentTasks.clear()
                currentTasks.addAll(repository.tasks(app.packageName, category))
                updateSelection()
                taskAdapter.notifyDataSetChanged()
            } else dialog.dismiss()
        }
        if (category == "Event") dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val game = EventImportClient.gameForPackage(app.packageName)
            if (game == null) {
                Toast.makeText(ctx, "가져오기는 젠레스 존 제로와 붕괴: 스타레일을 지원합니다", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val button = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            button.isEnabled = false
            button.text = "불러오는 중"
            val host = activity ?: return@setOnClickListener
            Thread {
                val result = runCatching { EventImportClient.fetch(game) }
                host.runOnUiThread {
                    if (!isAdded || host.isDestroyed || !dialog.isShowing) return@runOnUiThread
                    button.isEnabled = true
                    button.text = "가져오기"
                    result.onSuccess { events ->
                        if (events.isEmpty()) {
                            Toast.makeText(ctx, "가져올 이벤트가 없습니다", Toast.LENGTH_SHORT).show()
                        } else {
                            val selected = BooleanArray(events.size) { true }
                            AlertDialog.Builder(ctx)
                                .setTitle("이벤트 가져오기")
                                .setMultiChoiceItems(events.map { "${it.title}\n${EventDeadline.label(it.deadline)}" }.toTypedArray(), selected) { _, index, checked -> selected[index] = checked }
                                .setPositiveButton("추가") { _, _ ->
                                    val merged = EventImportClient.merge(currentTasks, events.filterIndexed { index, _ -> selected[index] })
                                    currentTasks.clear()
                                    currentTasks.addAll(merged)
                                    taskAdapter.notifyDataSetChanged()
                                }
                                .setNegativeButton("취소", null)
                                .show()
                        }
                    }.onFailure {
                        Toast.makeText(ctx, "이벤트를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun showOverlaySetupDialog() {
        val ctx = requireContext()
        val overlayOk = Settings.canDrawOverlays(ctx)
        val accessibilityOk = isAccessibilityEnabled()
        if (overlayOk && accessibilityOk) {
            Toast.makeText(ctx, "오버레이 설정이 완료되어 있습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val msg = buildString {
            if (!overlayOk) appendLine("• [다른 앱 위에 표시] 권한이 필요합니다.")
            if (!accessibilityOk) appendLine("• [접근성] 서비스 활성화가 필요합니다.")
        }
        AlertDialog.Builder(ctx)
            .setTitle("오버레이 설정")
            .setMessage(msg.trim())
            .apply {
                if (!overlayOk) setPositiveButton("다른 앱 위에 표시 설정") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                }
                if (!accessibilityOk) setNeutralButton("접근성 서비스 설정") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val ctx = requireContext()
        val service = "${ctx.packageName}/${GameOverlayService::class.java.canonicalName}"
        return try {
            val enabled = Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (enabled == 0) return false
            val services = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            services.split(":").any { it.equals(service, ignoreCase = true) }
        } catch (e: Exception) { false }
    }
}

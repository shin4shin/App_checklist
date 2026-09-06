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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.activity_main, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView(view)
        setupFab(view)
        setupMenu(view)
        setupSelectionBar(view)
        setupSaveButton(view)
        setupBackPress()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun setupSaveButton(view: View) {
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
                cancelReset(app.packageName)
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
                    for (pkg in selected) { deleteAddedApp(pkg); cancelReset(pkg) }
                    adapter.exitSelectionMode()
                    bar.visibility = View.GONE
                    refreshAppList()
                }
                .setNegativeButton("취소", null)
                .show()
        }
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
                btnSave.visibility = if (isSelecting) View.GONE else View.VISIBLE
                view.findViewById<FloatingActionButton>(R.id.fabAddApp)
                    .visibility = if (isSelecting) View.GONE else View.VISIBLE
            },
            onTaskClick = { app -> showTaskDialog(app) }
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

    fun scheduleReset(targetPackage: String, hour: Int, minute: Int = 0) {
        val ctx = requireContext()
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= Calendar.getInstance().timeInMillis) add(Calendar.DATE, 1)
        }
        val intent = Intent(ctx, ResetReceiver::class.java).apply { putExtra("target_package", targetPackage) }
        val pendingIntent = PendingIntent.getBroadcast(
            ctx, targetPackage.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms())
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            else
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        }
    }

    fun cancelReset(targetPackage: String) {
        val ctx = requireContext()
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ctx, ResetReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            ctx, targetPackage.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun saveAddedApp(pkg: String) {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("added_apps", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("apps", mutableSetOf())!!.toMutableSet()
        set.add(pkg); prefs.edit().putStringSet("apps", set).apply()
        if (ctx.getSharedPreferences("app_tasks", Context.MODE_PRIVATE).getString(pkg, null) == null) {
            saveTasksByCategory(pkg, mutableMapOf("Daily" to mutableListOf("일일 퀘스트")))
        }
    }

    private fun deleteAddedApp(pkg: String) {
        val prefs = requireContext().getSharedPreferences("added_apps", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("apps", mutableSetOf())!!.toMutableSet()
        set.remove(pkg); prefs.edit().putStringSet("apps", set).apply()
    }

    fun getAddedAppPackages(): Set<String> =
        requireContext().getSharedPreferences("added_apps", Context.MODE_PRIVATE)
            .getStringSet("apps", emptySet()) ?: emptySet()

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
        val allTasks  = loadTasksByCategory(app.packageName)
        val currentTasks = (allTasks[category] ?: emptyList()).toMutableList()
        val taskAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = currentTasks.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(layoutInflater.inflate(R.layout.item_task_edit, parent, false)) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                holder.itemView.findViewById<TextView>(R.id.tvTaskItem).text = currentTasks[position]
                holder.itemView.findViewById<TextView>(R.id.btnDeleteTask).setOnClickListener {
                    val pos = holder.adapterPosition
                    if (pos != RecyclerView.NO_POSITION) { currentTasks.removeAt(pos); notifyItemRemoved(pos) }
                }
            }
        }
        rvTasks.layoutManager = LinearLayoutManager(ctx)
        rvTasks.adapter = taskAdapter
        btnAdd.setOnClickListener {
            val text = etNewTask.text.toString().trim()
            if (text.isNotEmpty()) { currentTasks.add(text); taskAdapter.notifyItemInserted(currentTasks.size - 1); etNewTask.text.clear() }
        }
        AlertDialog.Builder(ctx)
            .setTitle("${app.name} - 할 일 목록")
            .setView(dialogView)
            .setPositiveButton("저장") { _, _ ->
                val taskMap = allTasks.toMutableMap().apply { put(category, currentTasks) }
                saveTasksByCategory(app.packageName, taskMap.mapValues { it.value.toMutableList() }.toMutableMap())
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun loadTasksByCategory(pkg: String): Map<String, List<String>> {
        val json = requireContext().getSharedPreferences("app_tasks", Context.MODE_PRIVATE).getString(pkg, "{}") ?: "{}"
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

    private fun saveTasksByCategory(pkg: String, taskMap: Map<String, MutableList<String>>) {
        val obj = JSONObject()
        for ((category, tasks) in taskMap) {
            if (tasks.isNotEmpty()) obj.put(category, JSONArray(tasks))
        }
        requireContext().getSharedPreferences("app_tasks", Context.MODE_PRIVATE)
            .edit().putString(pkg, obj.toString()).apply()
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

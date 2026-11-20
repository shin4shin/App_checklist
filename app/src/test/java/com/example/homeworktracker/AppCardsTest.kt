package com.example.homeworktracker

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatCheckBox
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppCardsTest {
    @Test fun longPressAppMenuFollowsTouchPosition() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val context = controller.get()
        TaskRepository(context).addApp(context.packageName, "Daily")
        val adapter = AppAdapter(context, mutableListOf(SharedGameInfo.resolve(context, context.packageName)!!), {}, {}, {}, {}, "Daily")
        val holder = adapter.onCreateViewHolder(android.widget.FrameLayout(context), 0)
        adapter.bindViewHolder(holder, 0)
        val header = holder.itemView.findViewById<View>(R.id.appCardHeader)
        header.layout(0, 0, 400, 100)
        for (x in listOf(40f, 320f)) {
            val event = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, x, 60f, 0)
            header.dispatchTouchEvent(event); event.recycle()
            header.performLongClick()
            val anchor = holder.itemView.findViewById<View>(R.id.appCardMenuAnchor)
            assertEquals(x, anchor.translationX, 0.01f)
            assertEquals(60f, anchor.translationY, 0.01f)
            val popup = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu()
            assertEquals("할 일 목록 편집", popup.menu.getItem(0).title)
            popup.dismiss()
        }
        controller.pause().stop().destroy()
    }

    @Test fun eventMenuHasOnlyEditAndDeleteAndEditingSavesTitleWithDuration() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val context = controller.get()
        val repo = TaskRepository(context)
        repo.addApp(context.packageName, "Event")
        val task = repo.tasks(context.packageName, "Event").first()
        val adapter = AppAdapter(context, mutableListOf(SharedGameInfo.resolve(context, context.packageName)!!), {}, {}, {}, {}, "Event")
        val holder = adapter.onCreateViewHolder(android.widget.FrameLayout(context), 0)
        adapter.bindViewHolder(holder, 0)
        val tasks = holder.itemView.findViewById<LinearLayout>(R.id.appCardTasks)
        val row = tasks.getChildAt(1) as LinearLayout
        row.getChildAt(2).performClick()
        val popup = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu()
        assertEquals(2, popup.menu.size())
        assertEquals("편집", popup.menu.getItem(0).title)
        assertEquals("삭제", popup.menu.getItem(1).title)
        org.robolectric.Shadows.shadowOf(popup).onMenuItemClickListener.onMenuItemClick(popup.menu.getItem(0))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        fun inputs(view: View): List<android.widget.EditText> = when (view) {
            is android.widget.EditText -> listOf(view)
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { inputs(view.getChildAt(it)) }
            else -> emptyList()
        }
        val fields = inputs(dialog.window!!.decorView)
        fields.first { it.hint == "제목" }.setText("바뀐 제목")
        fun buttons(view: View): List<com.google.android.material.button.MaterialButton> = when (view) {
            is com.google.android.material.button.MaterialButton -> listOf(view)
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
            else -> emptyList()
        }
        buttons(dialog.window!!.decorView).first { it.text == "시간 설정" }.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val timeDialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertNotSame(dialog, timeDialog)
        assertEquals("루틴", timeDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).text)
        assertTrue(buttons(timeDialog.window!!.decorView).any { it.text == "달력으로 설정" })
        timeDialog.findViewById<android.widget.NumberPicker>(R.id.eventDaysPicker)!!.value = 3
        timeDialog.findViewById<android.widget.NumberPicker>(R.id.eventHoursPicker)!!.value = 2
        timeDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(task.deadline, repo.tasks(context.packageName, "Event").first().deadline)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        val saved = repo.tasks(context.packageName, "Event").first()
        assertEquals(task.id, saved.id)
        assertEquals("바뀐 제목", saved.title)
        assertTrue(saved.deadline > System.currentTimeMillis())
        adapter.bindViewHolder(holder, 0)
        val labels = ((tasks.getChildAt(1) as LinearLayout).getChildAt(1) as LinearLayout)
        val time = (labels.getChildAt(1) as TextView).text.toString()
        assertTrue(time.endsWith(" 종료"))
        assertFalse(time.contains("남음"))
        controller.pause().stop().destroy()
    }

    @Test fun eachTabExpandsInlineTasksAndChecksWithoutOpeningEditor() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val context = controller.get()
        val repo = TaskRepository(context)
        for (category in TaskRepository.categories) {
            val pkg = context.packageName
            repo.addApp(pkg, category)
            var editors = 0
            val app = SharedGameInfo.resolve(context, pkg)!!
            val adapter = AppAdapter(context, mutableListOf(app), {}, {}, {}, { editors++ }, category)
            val holder = adapter.onCreateViewHolder(android.widget.FrameLayout(context), 0)
            adapter.bindViewHolder(holder, 0)
            val tasks = holder.itemView.findViewById<LinearLayout>(R.id.appCardTasks)
            assertEquals(View.VISIBLE, tasks.visibility)
            assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.btnSetTime).visibility)
            assertEquals(2, tasks.childCount)
            val row = tasks.getChildAt(1) as LinearLayout
            (row.getChildAt(0) as AppCompatCheckBox).performClick()
            assertTrue(repo.tasks(pkg, category).first().done)
            assertEquals(0, editors)
            TaskRepository.refreshExecutor.submit {}.get()
            adapter.bindViewHolder(holder, 0)
            assertTrue(holder.itemView.findViewById<TextView>(R.id.tvResetTime).text.contains("1/1"))
            assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.appCardComplete).visibility)
            assertEquals(android.graphics.Color.parseColor("#F0F6FC"), holder.itemView.findViewById<TextView>(R.id.tvAppName).currentTextColor)
            val checked = repo.tasks(pkg, category).first()
            val other = TaskItem(title = "힣 마지막")
            assertEquals(listOf(checked.id, other.id), adapter.orderedTasks(listOf(checked, other)).map { it.id })
            assertEquals(listOf(other.id, checked.id), adapter.orderedTasks(listOf(checked, other), System.currentTimeMillis() + 300_001).map { it.id })
            holder.itemView.findViewById<View>(R.id.appCardHeader).performClick()
            adapter.bindViewHolder(holder, 0)
            assertEquals(View.GONE, tasks.visibility)
            holder.itemView.findViewById<View>(R.id.appCardHeader).performClick()
            adapter.bindViewHolder(holder, 0)
            assertEquals(View.VISIBLE, tasks.visibility)
            adapter.enterSelectionMode()
            adapter.bindViewHolder(holder, 0)
            assertEquals(View.GONE, tasks.visibility)
            holder.itemView.findViewById<View>(R.id.appCardHeader).performClick()
            assertTrue(pkg in adapter.selectedPackages)
        }
        controller.pause().stop().destroy()
    }
}

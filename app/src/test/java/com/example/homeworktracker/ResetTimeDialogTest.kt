package com.example.homeworktracker

import android.content.Intent
import android.os.Looper
import android.widget.TimePicker
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResetTimeDialogTest {
    @Test fun weeklyRequiresADayAndShowsTheSelectedTime() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        var saved: Triple<Int, Int, Set<Int>>? = null
        val dialog = ResetTimeDialog.show(controller.get(), "Weekly", "테스트 앱", 9, 30, setOf(2)) { h, m, days -> saved = Triple(h, m, days) }
        shadowOf(Looper.getMainLooper()).idle()
        val picker = dialog.findViewById<TimePicker>(R.id.resetTimePicker)!!
        assertTrue(picker.is24HourView)
        assertEquals(9, picker.hour)
        val group = dialog.findViewById<ChipGroup>(R.id.resetDayGroup)!!
        (group.getChildAt(0) as Chip).isChecked = false
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertNull(saved)
        (group.getChildAt(2) as Chip).isChecked = true
        picker.hour = 17; picker.minute = 45
        assertTrue(dialog.findViewById<TextView>(R.id.resetSummary)!!.text.contains("17:45"))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(Triple(17, 45, setOf(4)), saved)
        assertFalse(dialog.isShowing)
        controller.pause().stop().destroy()
    }

    @Test fun cancelDoesNotSave() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        var saved = false
        val dialog = ResetTimeDialog.show(controller.get(), "Daily", "테스트 앱", 8, 15, emptySet()) { _, _, _ -> saved = true }
        shadowOf(Looper.getMainLooper()).idle()
        dialog.findViewById<TimePicker>(R.id.resetTimePicker)!!.hour = 23
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(saved)
        controller.pause().stop().destroy()
    }

    @Test fun savingTimeWritesImmediatelyWithoutASecondSave() {
        val context = RuntimeEnvironment.getApplication()
        val pkg = context.packageName
        TaskRepository(context).addApp(pkg, "Daily")
        val controller = Robolectric.buildActivity(HomeActivity::class.java,
            Intent(context, HomeActivity::class.java).putExtra("category", "Daily")).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val adapter = controller.get().findViewById<RecyclerView>(R.id.rvApps).adapter as AppAdapter
        adapter.showTimePicker(pkg)
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val picker = dialog.findViewById<TimePicker>(R.id.resetTimePicker)!!
        picker.hour = 6; picker.minute = 25
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(6 to 25, adapter.getSavedResetTime(pkg))
        assertFalse(adapter.hasChanges())
        controller.pause().stop().destroy()
    }
}

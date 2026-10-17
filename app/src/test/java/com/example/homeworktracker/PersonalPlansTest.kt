package com.example.homeworktracker

import android.os.Looper
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalPlansTest {
    @Test fun directEditorSavesTitleAndCalendarTimeOnlyWhenConfirmed() {
        val context = RuntimeEnvironment.getApplication()
        PlannerMode.setPersonal(context, true)
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        controller.get().findViewById<View>(R.id.addPersonalPlan).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val editor = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        editor.getButton(-1).performClick()
        assertTrue(PersonalPlanRepository(context).plans().isEmpty())
        editor.findViewById<android.widget.EditText>(R.id.personalPlanTitle)!!.setText("운동하기")
        editor.findViewById<View>(R.id.personalPlanSetTime)!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val timeDialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val date = timeDialog.findViewById<android.widget.DatePicker>(R.id.personalDeadlineDate)!!
        val time = timeDialog.findViewById<android.widget.TimePicker>(R.id.personalDeadlineTime)!!
        date.updateDate(2030, 8, 22)
        time.hour = 15
        time.minute = 37
        timeDialog.getButton(-1).performClick()
        assertTrue(PersonalPlanRepository(context).plans().isEmpty())
        editor.getButton(-1).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val saved = PersonalPlanRepository(context).plans().single()
        assertEquals("운동하기", saved.title)
        val expected = java.util.Calendar.getInstance().apply { clear(); set(2030, 8, 22, 15, 37, 0) }.timeInMillis
        assertEquals(expected, saved.deadline)
        assertFalse(saved.done)
        assertTrue(TaskRepository(context).packages().isEmpty())
        val list = controller.get().findViewById<android.widget.LinearLayout>(R.id.personalPlanList)
        val card = list.getChildAt(0) as com.google.android.material.card.MaterialCardView
        val row = card.getChildAt(0) as android.widget.LinearLayout
        (row.getChildAt(0) as com.google.android.material.checkbox.MaterialCheckBox).performClick()
        assertTrue(PersonalPlanRepository(context).plans().single().done)
        list.getChildAt(0).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val reopened = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertEquals("운동하기", reopened.findViewById<android.widget.EditText>(R.id.personalPlanTitle)!!.text.toString())
        reopened.findViewById<android.widget.EditText>(R.id.personalPlanTitle)!!.setText("산책하기")
        reopened.getButton(-1).performClick()
        assertEquals("산책하기", PersonalPlanRepository(context).plans().single().title)
        assertTrue(PersonalPlanRepository(context).plans().single().done)
        controller.pause().stop().destroy()
    }

    @Test fun modesPreserveIndependentPlansAndPersistAcrossRecreation() {
        val context = RuntimeEnvironment.getApplication()
        val apps = TaskRepository(context)
        apps.addApp("game", "Event")
        apps.setDone("game", "Event", true)
        val existing = apps.tasks("game", "Event")
        val plans = PersonalPlanRepository(context)
        val plan = TaskItem(title = "책 읽기", deadline = System.currentTimeMillis() + 86_400_000L)
        plans.save(plan)
        plans.setDone(plan.id, true)
        plans.save(plan.copy(title = "책 두 장 읽기"))
        assertTrue(plans.plans().single().done)
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        controller.get().findViewById<View>(R.id.homeModeSwitch).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(PlannerMode.isPersonal(context))
        assertNotNull(controller.get().findViewById<View>(R.id.addPersonalPlan))
        assertEquals(existing, apps.tasks("game", "Event"))
        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(PlannerMode.isPersonal(context))
        assertNotNull(controller.get().findViewById<View>(R.id.addPersonalPlan))
        controller.get().findViewById<View>(R.id.homeModeSwitch).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(PlannerMode.isPersonal(context))
        assertNotNull(controller.get().findViewById<View>(R.id.homeProgressTabs))
        assertEquals("책 두 장 읽기", plans.plans().single().title)
        assertEquals(plan.deadline, plans.plans().single().deadline)
        plans.delete(plan.id)
        assertTrue(plans.plans().isEmpty())
        assertEquals(existing, apps.tasks("game", "Event"))
        controller.pause().stop().destroy()
    }
}

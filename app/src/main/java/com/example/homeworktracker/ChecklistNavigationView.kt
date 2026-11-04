package com.example.homeworktracker

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.bottomnavigation.BottomNavigationView

class ChecklistNavigationView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : BottomNavigationView(context, attrs) {
    override fun getMaxItemCount(): Int = 6
}

package com.example.homeworktracker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val dateFormat = SimpleDateFormat("yyyy년 M월 d일 EEEE", Locale.KOREAN)
        view.findViewById<TextView>(R.id.tvTodayDate).text = dateFormat.format(Date())

        view.findViewById<View>(R.id.btnGoDaily).setOnClickListener {
            requireActivity().findViewById<BottomNavigationView>(R.id.bottomNav)
                ?.selectedItemId = R.id.nav_daily
        }
    }
}

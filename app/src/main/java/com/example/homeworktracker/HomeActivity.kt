package com.example.homeworktracker

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class HomeActivity : AppCompatActivity() {

    private var currentNavId = R.id.nav_home

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        if (savedInstanceState == null) {
            showFragment(R.id.nav_home)
        }

        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_daily) {
                startActivity(Intent(this, MainActivity::class.java))
                // 선택 상태를 홈으로 유지
                findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = currentNavId
                false
            } else {
                currentNavId = item.itemId
                showFragment(item.itemId)
                true
            }
        }
    }

    private fun showFragment(navId: Int) {
        val fragment: Fragment = when (navId) {
            R.id.nav_home     -> HomeFragment()
            R.id.nav_weekly   -> PlaceholderFragment.newInstance("위클리")
            R.id.nav_monthly  -> PlaceholderFragment.newInstance("먼슬리")
            R.id.nav_more     -> MoreFragment()
            else              -> HomeFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}

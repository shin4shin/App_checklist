package com.example.homeworktracker

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<View>(R.id.cardDaily).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        listOf(R.id.cardWeekly, R.id.cardMonthly, R.id.cardEvent, R.id.cardPremium)
            .forEach { id ->
                findViewById<View>(id).setOnClickListener {
                    showInDevelopmentDialog()
                }
            }
    }

    private fun showInDevelopmentDialog() {
        AlertDialog.Builder(this)
            .setMessage("현재 개발중입니다.")
            .setPositiveButton("확인", null)
            .show()
    }
}

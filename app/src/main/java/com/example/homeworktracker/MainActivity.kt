package com.example.homeworktracker
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
/** Compatibility entry for previously pinned shortcuts and widgets. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, HomeActivity::class.java).apply {
            action = intent.action
            putExtra("category", "Daily")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }
}

package com.example.gemmabuddy

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private val segIds = listOf(R.id.seg1, R.id.seg2, R.id.seg3, R.id.seg4)
    private var segStep = 0
    private val animHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // セグメントバーアニメ（4 → 10 まで点灯させる）
        animateSegments()

        // 1500ms 後に MainActivity へ
        Handler(Looper.getMainLooper()).postDelayed({
            animHandler.removeCallbacksAndMessages(null)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 1500)
    }

    private fun animateSegments() {
        animHandler.postDelayed(object : Runnable {
            override fun run() {
                if (segStep < segIds.size) {
                    findViewById<View>(segIds[segStep])
                        ?.setBackgroundColor(getColor(R.color.nr_cyan))
                    segStep++
                    animHandler.postDelayed(this, 200)
                }
            }
        }, 300)
    }

    override fun onDestroy() {
        super.onDestroy()
        animHandler.removeCallbacksAndMessages(null)
    }
}

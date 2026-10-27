package com.example.svet

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Экран «Свет дали!» — включается поверх блокировки. */
class AlarmActivity : Activity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val test = intent.getBooleanExtra("test", false)
        val last = Store.load(this).lastOrNull()

        val title = TextView(this).apply {
            text = if (test) "Проверка сигнала" else "⚡ Свет дали!"
            textSize = 38f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(255, 193, 7))
            gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            text = when {
                test -> "Так будет звучать сигнал, когда появится свет."
                last != null && last.on -> "Примерно в ${Fmt.clock(last.ts)}\n${last.note}"
                else -> ""
            }
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(32))
        }
        val stop = Button(this).apply {
            text = "Выключить сигнал"
            textSize = 20f
            isAllCaps = false
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.rgb(255, 193, 7))
            }
            setOnClickListener { stopAlarm() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(title)
            addView(sub)
            addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80)))
        }
        setContentView(root)
    }

    private fun stopAlarm() {
        Alarm.stop()
        stopService(Intent(this, WatchService::class.java))
        getSystemService(NotificationManager::class.java).cancel(WatchService.ID_ALARM)
        finish()
    }

    override fun onBackPressed() {
        stopAlarm()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

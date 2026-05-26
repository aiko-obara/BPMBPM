package com.example.gemmabuddy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService

class StepCounterService : LifecycleService() {

    private lateinit var stepManager: StepCounterManager

    override fun onCreate() {
        super.onCreate()
        stepManager = StepCounterManager(this)
        stepManager.start()
        startForegroundCompat()
        Log.i(TAG, "StepCounterService 起動")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        stepManager.stop()
        Log.i(TAG, "StepCounterService 停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startForegroundCompat() {
        val channelId = "step_counter_channel"
        val channel = NotificationChannel(
            channelId, "歩数カウンター", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "歩数を常時記録中" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("歩数を記録中")
            .setContentText("今日の歩数: ${stepManager.getTodaySteps()}")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "StepCounterService"
        const val NOTIF_ID = 1002
    }
}

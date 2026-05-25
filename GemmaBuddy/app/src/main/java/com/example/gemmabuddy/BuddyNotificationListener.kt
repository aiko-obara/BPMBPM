package com.example.gemmabuddy

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class BuddyNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return  // 自分自身の通知は無視

        val extras = sbn.notification?.extras ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: return

        if (text.isBlank() || text.length < 5) return

        Log.d(TAG, "通知受信: $pkg / $text")

        val intent = Intent(ACTION_NOTIFICATION_RECEIVED).apply {
            `package` = packageName
            putExtra(EXTRA_NOTIFICATION_TEXT, text)
        }
        sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "BuddyNotificationListener"
        const val ACTION_NOTIFICATION_RECEIVED = "com.example.gemmabuddy.ACTION_NOTIFICATION_RECEIVED"
        const val EXTRA_NOTIFICATION_TEXT = "notification_text"
    }
}

package com.example.gemmabuddy

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import java.io.File

/**
 * ホーム画面ウィジェット。アクティブバディのサムネ + 今日の歩数 + 直近コメントを表示する。
 * 更新は updatePeriodMillis（30分）と、OverlayService からの [requestUpdate] による push。
 */
class BuddyWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id -> updateWidget(context, appWidgetManager, id) }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_buddy)

        // 今日の歩数（StepCounterManager と同じ prefs）
        val stepPrefs = context.getSharedPreferences(
            StepCounterManager.PREFS_NAME, Context.MODE_PRIVATE
        )
        val steps = stepPrefs.getInt(StepCounterManager.PREF_TODAY_STEPS, 0)
        views.setTextViewText(R.id.widget_steps, context.getString(R.string.widget_steps, steps))

        // 直近コメント
        val prefs = context.getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val comment = prefs.getString(OverlayService.PREF_LAST_COMMENT, null)
            ?: context.getString(R.string.widget_default_comment)
        views.setTextViewText(R.id.widget_comment, comment)

        // バディサムネ（custom_character.png）
        val file = File(context.filesDir, OverlayService.CUSTOM_CHAR_FILE)
        val bmp = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        if (bmp != null) {
            views.setImageViewBitmap(R.id.widget_buddy_thumb, bmp)
        } else {
            views.setImageViewResource(R.id.widget_buddy_thumb, R.drawable.ic_buddy_placeholder)
        }

        // タップでアプリを開く
        val launchIntent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_root, pending)

        manager.updateAppWidget(widgetId, views)
    }

    companion object {
        /** 任意のタイミングで全ウィジェットを更新する。 */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, BuddyWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val provider = BuddyWidgetProvider()
            ids.forEach { id -> provider.updateWidget(context, manager, id) }
        }
    }
}

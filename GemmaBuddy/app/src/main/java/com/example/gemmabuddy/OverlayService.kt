package com.example.gemmabuddy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class OverlayService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private var overlayRoot: FrameLayout? = null
    private var characterView: CharacterOverlayView? = null
    private var speechBubbleView: SpeechBubbleView? = null

    private var screenCaptureManager: ScreenCaptureManager? = null
    private var gemmaManager: GemmaManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var monitorRunnable: Runnable? = null

    private var charLayoutParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        gemmaManager = GemmaManager(this)
        // Android 14+はmediaProjection型でstartForeground()する前に
        // 画面キャプチャ権限が必要なため、起動時はspecialUseのみで開始
        startForegroundCompat()
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                val projectionData = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
                val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
                if (projectionData != null && modelPath != null) {
                    startCapture(projectionData, modelPath)
                }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startCapture(projectionData: Intent, modelPath: String) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Android 14+: getMediaProjection()を呼ぶ前にMEDIA_PROJECTION型でstartForegroundが必要
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }

        val projection = projectionManager.getMediaProjection(RESULT_OK_CODE, projectionData)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        screenCaptureManager = ScreenCaptureManager(
            projection, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi
        ).also { it.start() }

        lifecycleScope.launch {
            val ok = gemmaManager!!.initialize(modelPath)
            if (ok) {
                speechBubbleView?.showText("モデル読み込み完了！画面を見ているよ〜")
                scheduleMonitoring()
            } else {
                speechBubbleView?.showText("モデルの読み込みに失敗しました...")
            }
        }
    }

    private fun scheduleMonitoring() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val intervalMs = prefs.getLong(PREF_INTERVAL_MS, DEFAULT_INTERVAL_MS)

        monitorRunnable = object : Runnable {
            override fun run() {
                performMonitorCycle()
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.postDelayed(monitorRunnable!!, intervalMs)
        Log.i(TAG, "監視スケジュール開始: ${intervalMs / 1000}秒毎")
    }

    private fun performMonitorCycle() {
        val captureManager = screenCaptureManager ?: return
        val gemma = gemmaManager ?: return

        lifecycleScope.launch {
            val bitmap = captureManager.captureScreen() ?: return@launch
            val comment = gemma.analyzeScreen(bitmap)
            bitmap.recycle()
            handler.post {
                speechBubbleView?.showText(comment)
            }
        }
    }

    private fun setupOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 40
            y = 200
        }
        charLayoutParams = params

        overlayRoot = FrameLayout(this)
        val inflater = LayoutInflater.from(this)
        inflater.inflate(R.layout.overlay_character, overlayRoot, true)

        characterView = overlayRoot!!.findViewById(R.id.character_view)
        speechBubbleView = overlayRoot!!.findViewById(R.id.speech_bubble)

        characterView?.onMoveListener = { dx, dy ->
            charLayoutParams?.let { lp ->
                lp.x += dx.toInt()
                lp.y -= dy.toInt()
                windowManager.updateViewLayout(overlayRoot, lp)
            }
        }

        characterView?.onTapListener = {
            speechBubbleView?.showText("考え中...")
            performMonitorCycle()
        }

        windowManager.addView(overlayRoot, params)
        Log.i(TAG, "オーバーレイ表示")
    }

    private fun buildNotification(): Notification {
        val channelId = "gemmabuddy_channel"
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("GemmaBuddy 稼働中")
            .setContentText("画面を監視しています")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .build()
    }

    private fun startForegroundCompat() {
        val channelId = "gemmabuddy_channel"
        val channel = NotificationChannel(
            channelId, "GemmaBuddy", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "キャラクター常駐サービス" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = buildNotification()

        when {
            // Android 14+: 起動時はspecialUse型のみ（mediaProjection権限はまだない）
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            // Android 10-13: mediaProjection型で開始可能
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else ->
                startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        monitorRunnable?.let { handler.removeCallbacks(it) }
        screenCaptureManager?.stop()
        gemmaManager?.close()
        overlayRoot?.let { windowManager.removeView(it) }
        super.onDestroy()
        Log.i(TAG, "OverlayService 停止")
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        private const val TAG = "OverlayService"
        const val ACTION_START = "com.example.gemmabuddy.ACTION_START"
        const val ACTION_STOP = "com.example.gemmabuddy.ACTION_STOP"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_MODEL_PATH = "model_path"
        const val PREFS_NAME = "gemmabuddy_prefs"
        const val PREF_INTERVAL_MS = "interval_ms"
        const val DEFAULT_INTERVAL_MS = 5 * 60 * 1000L
        private const val NOTIFICATION_ID = 1001
        private const val RESULT_OK_CODE = -1
    }
}

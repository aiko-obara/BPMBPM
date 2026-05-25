package com.example.gemmabuddy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
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
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

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

    private var dialogInputRoot: View? = null
    private var dialogTurnCount = 0
    private val dialogTimeoutRunnable = Runnable { exitDialogMode() }

    private var currentEmotion = BuddyEmotion.NEUTRAL
    private var lastReactionAt = 0L

    private var clipboardManager: android.content.ClipboardManager? = null
    private val clipboardListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        val text = clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString() ?: return@OnPrimaryClipChangedListener
        if (text.length >= 10 && canReact()) {
            performReactionToEvent("クリップボードにコピーした内容: $text")
        }
    }

    private lateinit var memoryStore: MemoryStore
    private lateinit var memory: CharacterMemory
    private var consolidating = false
    private var isShuttingDown = false

    private val characterReloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_RELOAD_CHARACTER -> {
                    loadCharacterAssets()
                    handler.post { updateBubbleStyle() }
                }
                ACTION_RELOAD_PERSONALITY -> {
                    gemmaManager?.rebuildConversation(memoryStore.buildContextDigest(), force = true)
                    handler.post { updateBubbleStyle() }
                    Log.i(TAG, "性格変更反映")
                }
                BuddyNotificationListener.ACTION_NOTIFICATION_RECEIVED -> {
                    val text = intent.getStringExtra(BuddyNotificationListener.EXTRA_NOTIFICATION_TEXT) ?: return
                    if (canReact()) performReactionToEvent("通知が来たよ: $text")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        memoryStore = MemoryStore(this)
        memory = memoryStore.load()
        Log.i(TAG, "記憶読み込み完了: events=${memory.recentEvents.size}, " +
                "consolidatedAt=${memory.lastConsolidatedAt}")
        gemmaManager = GemmaManager(this)
        // Android 14+はmediaProjection型でstartForeground()する前に
        // 画面キャプチャ権限が必要なため、起動時はspecialUseのみで開始
        startForegroundCompat()
        setupOverlay()
        loadCharacterAssets()
        val filter = IntentFilter().apply {
            addAction(ACTION_RELOAD_CHARACTER)
            addAction(ACTION_RELOAD_PERSONALITY)
            addAction(BuddyNotificationListener.ACTION_NOTIFICATION_RECEIVED)
        }
        registerReceiver(characterReloadReceiver, filter, RECEIVER_NOT_EXPORTED)

        clipboardManager = getSystemService(android.content.ClipboardManager::class.java)
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
    }

    private fun loadCharacterAssets() {
        val file = File(filesDir, CUSTOM_CHAR_FILE)
        if (!file.exists()) return
        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val normal2 = File(filesDir, BuddyStore.CUSTOM_CHAR_NORMAL2_FILE)
                .takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
            val speaking = File(filesDir, BuddyStore.CUSTOM_CHAR_SPEAKING_FILE)
                .takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }

            handler.post {
                if (normal2 != null || speaking != null) {
                    characterView?.setFrames(listOfNotNull(bitmap, normal2), speaking)
                } else {
                    characterView?.setCustomBitmap(bitmap)
                }
            }
            Log.i(TAG, "カスタムキャラクター読み込み完了 (normal2=${normal2 != null}, speaking=${speaking != null})")
        } catch (e: Exception) {
            Log.e(TAG, "カスタムキャラクター読み込み失敗", e)
        }
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

        Log.i(TAG, "モデル初期化開始: $modelPath")
        speechBubbleView?.showText("モデル読み込み中...少し待ってね")
        lifecycleScope.launch {
            val ok = gemmaManager!!.initialize(modelPath, memoryStore.buildContextDigest())
            if (isShuttingDown) return@launch
            if (ok) {
                handler.post { speechBubbleView?.showText("準備完了！タップしてね♪") }
                scheduleMonitoring()
            } else {
                handler.post { speechBubbleView?.showText("モデルの読み込みに失敗しました...") }
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
        val gemma = gemmaManager ?: return
        if (!gemma.isReady()) return
        Log.i(TAG, "コメント生成開始")
        val capture = screenCaptureManager
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val screenshot = capture?.captureScreen()
            Log.i(TAG, "スクリーンショット: ${if (screenshot != null) "${screenshot.width}x${screenshot.height}" else "null"}")
            val (comment, emotion) = gemma.generateComment(screenshot)
            if (isShuttingDown) return@launch
            Log.i(TAG, "コメント生成完了: $comment [emotion=$emotion]")
            handler.post {
                applyEmotion(emotion)
                speechBubbleView?.showText(comment)
                characterView?.setSpeaking(true)
            }
            memory.addEvent(comment)
            memoryStore.save(memory)
            maybeConsolidate(gemma)
        }
    }

    private fun performChitchat() {
        val gemma = gemmaManager ?: return
        if (!gemma.isReady()) return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val (comment, emotion) = gemma.generateChitchat()
            if (isShuttingDown) return@launch
            handler.post {
                applyEmotion(emotion)
                speechBubbleView?.showText(comment)
                characterView?.setSpeaking(true)
            }
            memory.addEvent(comment)
            memoryStore.save(memory)
            maybeConsolidate(gemma)
        }
    }

    private fun maybeConsolidate(gemma: GemmaManager) {
        if (consolidating) return
        if (!memory.shouldConsolidate()) return
        consolidating = true
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                Log.i(TAG, "Consolidation 実行")
                val consolidator = MemoryConsolidator(gemma)
                val updated = consolidator.consolidate(memory)
                if (updated != null) {
                    memoryStore.save(updated)
                    gemma.rebuildConversation(memoryStore.buildContextDigest())
                    Log.i(TAG, "Consolidation 完了・人格更新")
                } else {
                    Log.w(TAG, "Consolidation 失敗、profile は据え置き")
                }
            } finally {
                consolidating = false
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
        speechBubbleView?.onHideListener = { characterView?.setSpeaking(false) }

        characterView?.onMoveListener = { dx, dy ->
            charLayoutParams?.let { lp ->
                lp.x += dx.toInt()
                lp.y -= dy.toInt()
                windowManager.updateViewLayout(overlayRoot, lp)
            }
        }

        characterView?.onLongPressListener = {
            if (!isShuttingDown) {
                isShuttingDown = true
                monitorRunnable?.let { handler.removeCallbacks(it) }
                exitDialogMode()

                val farewell = FAREWELL_COMMENTS.random()
                speechBubbleView?.onTypewriterDoneListener = {
                    val assetFiles = assets.list("elements/escape")
                        ?.toList()?.sorted() ?: emptyList()
                    val frames = assetFiles.mapNotNull { name ->
                        runCatching {
                            BitmapFactory.decodeStream(assets.open("elements/escape/$name"))
                        }.getOrNull()
                    }
                    if (frames.isNotEmpty()) {
                        characterView?.playEscapeAnimation(frames) { stopSelf() }
                    } else {
                        stopSelf()
                    }
                }
                speechBubbleView?.showText(farewell)
                characterView?.setSpeaking(true)
            }
        }

        characterView?.onTapListener = {
            when {
                gemmaManager?.isReady() != true -> {
                    speechBubbleView?.showText("まだ読み込み中...もう少し待ってね！")
                    characterView?.setSpeaking(true)
                }
                gemmaManager?.isInferring() == true -> { /* 生成完了まで無視 */ }
                screenCaptureManager == null -> {
                    speechBubbleView?.showText("考え中だよ〜！")
                    characterView?.setSpeaking(true)
                    performChitchat()
                }
                else -> {
                    speechBubbleView?.showText("考え中だよ〜！")
                    characterView?.setSpeaking(true)
                    performMonitorCycle()
                }
            }
        }

        speechBubbleView?.setOnClickListener {
            if (gemmaManager?.isReady() == true && !isShuttingDown && dialogInputRoot == null) {
                enterDialogMode()
            }
        }

        updateBubbleStyle()
        windowManager.addView(overlayRoot, params)
        Log.i(TAG, "オーバーレイ表示")
    }

    private fun updateBubbleStyle() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val personality = BuddyPersonality.load(prefs)
        val buddyName = BuddyStore(this).getActive()?.name ?: "BUDDY"
        val color = if (currentEmotion == BuddyEmotion.NEUTRAL) personality.color else currentEmotion.accentColor
        speechBubbleView?.setStyle(color, buddyName)
    }

    private fun applyEmotion(emotion: BuddyEmotion) {
        currentEmotion = emotion
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val buddyName = BuddyStore(this).getActive()?.name ?: "BUDDY"
        speechBubbleView?.setStyle(emotion.accentColor, buddyName)
        characterView?.setEmotion(emotion)
    }

    private fun canReact(): Boolean {
        val now = System.currentTimeMillis()
        return !isShuttingDown &&
                gemmaManager?.isReady() == true &&
                gemmaManager?.isInferring() == false &&
                now - lastReactionAt > REACTION_COOLDOWN_MS
    }

    private fun performReactionToEvent(eventText: String) {
        val gemma = gemmaManager ?: return
        lastReactionAt = System.currentTimeMillis()
        handler.post {
            speechBubbleView?.showText("ん？")
            characterView?.setSpeaking(true)
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val (response, emotion) = gemma.generateResponse(eventText)
            if (isShuttingDown) return@launch
            memory.addEvent("反応: $response")
            memoryStore.save(memory)
            handler.post {
                applyEmotion(emotion)
                speechBubbleView?.showText(response)
                characterView?.setSpeaking(true)
            }
        }
    }

    private fun enterDialogMode() {
        dialogTurnCount = 0
        val panelView = LayoutInflater.from(this).inflate(R.layout.dialog_input_panel, null)
        val etInput = panelView.findViewById<EditText>(R.id.et_dialog_input)

        panelView.findViewById<Button>(R.id.btn_dialog_send).setOnClickListener {
            val msg = etInput.text.toString().trim()
            if (msg.isNotEmpty() && gemmaManager?.isInferring() == false) {
                etInput.setText("")
                sendDialogMessage(msg)
            }
        }

        etInput.setOnEditorActionListener { _, _, _ ->
            val msg = etInput.text.toString().trim()
            if (msg.isNotEmpty() && gemmaManager?.isInferring() == false) {
                etInput.setText("")
                sendDialogMessage(msg)
            }
            true
        }

        panelView.findViewById<Button>(R.id.btn_dialog_close).setOnClickListener {
            exitDialogMode()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        dialogInputRoot = panelView
        windowManager.addView(panelView, params)
        etInput.requestFocus()
        scheduleDialogTimeout()
        Log.i(TAG, "対話モード開始")
    }

    private fun exitDialogMode() {
        handler.removeCallbacks(dialogTimeoutRunnable)
        dialogInputRoot?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        dialogInputRoot = null
        dialogTurnCount = 0
        handler.post {
            applyEmotion(BuddyEmotion.NEUTRAL)
            updateBubbleStyle()
            speechBubbleView?.showText("またね〜！")
            characterView?.setSpeaking(true)
        }
        Log.i(TAG, "対話モード終了")
    }

    private fun sendDialogMessage(message: String) {
        val gemma = gemmaManager ?: return
        handler.removeCallbacks(dialogTimeoutRunnable)
        handler.post {
            speechBubbleView?.showText("考え中...")
            characterView?.setSpeaking(true)
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val (response, emotion) = gemma.generateResponse(message)
            if (isShuttingDown) return@launch
            dialogTurnCount++
            memory.addEvent("対話: $message → $response")
            memoryStore.save(memory)
            handler.post {
                applyEmotion(emotion)
                speechBubbleView?.showText(response)
                characterView?.setSpeaking(true)
                if (dialogTurnCount >= DIALOG_MAX_TURNS) {
                    handler.postDelayed({ exitDialogMode() }, 3000)
                } else {
                    scheduleDialogTimeout()
                }
            }
        }
    }

    private fun scheduleDialogTimeout() {
        handler.postDelayed(dialogTimeoutRunnable, DIALOG_TIMEOUT_MS)
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
        isShuttingDown = true
        handler.removeCallbacksAndMessages(null)
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        unregisterReceiver(characterReloadReceiver)
        screenCaptureManager?.stop()
        dialogInputRoot?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlayRoot?.let { windowManager.removeView(it) }
        super.onDestroy()           // lifecycleScope のコルーチンをキャンセル
        gemmaManager?.close()       // コルーチンキャンセル後にネイティブリソース解放
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
        const val ACTION_RELOAD_CHARACTER = "com.example.gemmabuddy.ACTION_RELOAD_CHARACTER"
        const val ACTION_RELOAD_PERSONALITY = "com.example.gemmabuddy.ACTION_RELOAD_PERSONALITY"
        const val CUSTOM_CHAR_FILE = "custom_character.png"

        private const val DIALOG_MAX_TURNS = 5
        private const val DIALOG_TIMEOUT_MS = 30_000L
        private const val REACTION_COOLDOWN_MS = 60_000L

        private val FAREWELL_COMMENTS = listOf(
            "おやすみ〜！またね♪",
            "じゃあね！またすぐ来てね！",
            "バイバイ！寂しいな〜",
            "またね！待ってるよ〜！",
            "おやすみなさい...ゆっくり休んでね",
            "またね！次も一緒に頑張ろうね♪",
            "バイバーイ！元気でね！"
        )

    }
}

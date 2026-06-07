package com.example.gemmabuddy

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    private var gemmaManager: GemmaManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var monitorRunnable: Runnable? = null
    private var proactiveRunnable: Runnable? = null

    private var charLayoutParams: WindowManager.LayoutParams? = null

    private var dialogInputRoot: View? = null
    private var dialogTurnCount = 0
    private val dialogTimeoutRunnable = Runnable { exitDialogMode() }

    private var currentEmotion = BuddyEmotion.NEUTRAL
    private var lastReactionAt = 0L
    private var stepManager: StepCounterManager? = null

    private var mediaProjection: MediaProjection? = null
    private var screenCapture: ScreenCaptureManager? = null
    private var visionEnabled = false
    private var lastVisionAt = 0L
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            handler.post { disableVision() }
        }
    }

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
    private var isTransitioning = false
    private var coverRoot: FrameLayout? = null

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
                ACTION_HIDE_CHARACTER -> {
                    handler.post { overlayRoot?.visibility = View.GONE }
                }
                ACTION_SHOW_CHARACTER -> {
                    handler.post { overlayRoot?.visibility = View.VISIBLE }
                }
                ACTION_RESET_MEMORY -> {
                    // DB はリセット済み。稼働中サービスが保持する in-memory 状態も破棄しないと
                    // 次の save() で古い記憶が書き戻され、再起動時に復元されてしまう。
                    memory = CharacterMemory()
                    consolidating = false
                    gemmaManager?.rebuildConversation(memoryStore.buildContextDigest(), force = true)
                    Log.i(TAG, "記憶リセットを反映")
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
            addAction(ACTION_RESET_MEMORY)
            addAction(ACTION_HIDE_CHARACTER)
            addAction(ACTION_SHOW_CHARACTER)
            addAction(BuddyNotificationListener.ACTION_NOTIFICATION_RECEIVED)
        }
        registerReceiver(characterReloadReceiver, filter, RECEIVER_NOT_EXPORTED)

        clipboardManager = getSystemService(android.content.ClipboardManager::class.java)
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stepMode = prefs.getString(PREF_STEP_MODE, "1") ?: "1"
        if (stepMode == "1") {
            stepManager = StepCounterManager(this).apply {
                onMilestoneReached = { milestone ->
                    if (!isShuttingDown && gemmaManager?.isInferring() == false) {
                        val en = BuddyLanguage.isEnglish(this@OverlayService)
                        val comment = stepMilestones(en)[milestone]
                            ?: if (en) "$milestone steps!" else "${milestone}歩達成！"
                        handler.post { showComment(comment, BuddyEmotion.EXCITED) }
                    }
                }
                start()
            }
        }
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

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modelPath: String? = when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_MODEL_PATH) ?: return START_STICKY
                prefs.edit().putString(PREF_LAST_MODEL_PATH, path).apply()
                path
            }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_ENABLE_VISION -> {
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                val code = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0)
                if (data != null) enableVision(code, data)
                return START_STICKY
            }
            ACTION_DISABLE_VISION -> { disableVision(); return START_STICKY }
            else -> prefs.getString(PREF_LAST_MODEL_PATH, null)  // Android 再起動による null インテント
        }
        val gm = gemmaManager ?: return START_STICKY
        if (modelPath == null || gm.isReady()) return START_STICKY
        speechBubbleView?.showText(loc("モデル読み込み中...少し待ってね", "Loading model... hang on"))
        lifecycleScope.launch {
            val ok = gm.initialize(modelPath, memoryStore.buildContextDigest())
            if (isShuttingDown) return@launch
            if (ok) {
                handler.post { speechBubbleView?.showText(loc("準備完了！タップしてね♪", "Ready! Tap to chat ♪")) }
                scheduleMonitoring()
                scheduleProactive()
            } else {
                handler.post { speechBubbleView?.showText(loc("モデルの読み込みに失敗しました...", "Failed to load the model...")) }
            }
        }
        return START_STICKY
    }

    private fun scheduleMonitoring() {
        // 既にスケジュール済みなら二重登録を防ぐ
        monitorRunnable?.let { handler.removeCallbacks(it) }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val intervalMs = prefs.getLong(PREF_INTERVAL_MS, DEFAULT_INTERVAL_MS)

        monitorRunnable = object : Runnable {
            override fun run() {
                val idle = gemmaManager?.isReady() == true && !isShuttingDown &&
                        gemmaManager?.isInferring() == false && dialogInputRoot == null
                if (idle) {
                    if (isVisionDue()) {
                        performScreenComment()
                    } else {
                        performChitchat()
                    }
                }
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.postDelayed(monitorRunnable!!, intervalMs)
        Log.i(TAG, "自動発話スケジュール開始: ${intervalMs / 1000}秒毎")
    }

    /** Vision が有効でキャプチャ可能、かつ前回の画面コメントから十分間隔が空いていれば true。 */
    private fun isVisionDue(): Boolean {
        if (!visionEnabled || screenCapture == null) return false
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val visionInterval = prefs.getLong(PREF_VISION_INTERVAL_MS, DEFAULT_VISION_INTERVAL_MS)
        return System.currentTimeMillis() - lastVisionAt >= visionInterval
    }

    private fun performScreenComment() {
        val gemma = gemmaManager ?: return
        val capture = screenCapture ?: return
        if (!gemma.isReady()) return
        lastVisionAt = System.currentTimeMillis()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val screenshot = capture.captureScreen()
            if (screenshot == null) {
                Log.w(TAG, "画面キャプチャ取得失敗、雑談にフォールバック")
                if (!isShuttingDown) performChitchat()
                return@launch
            }
            val (comment, emotion) = gemma.generateCommentForScreen(screenshot)
            screenshot.recycle()
            if (isShuttingDown) return@launch
            handler.post { showComment(comment, emotion) }
            memory.addEvent("画面: $comment")
            memoryStore.save(memory)
            maybeConsolidate(gemma)
        }
    }

    /**
     * MediaProjection の許可結果を受けて画面キャプチャを開始する。
     * Android 14+ では getMediaProjection の前に mediaProjection 型で
     * startForeground を呼び直す必要がある。
     */
    private fun enableVision(resultCode: Int, data: Intent) {
        if (visionEnabled) disableVision()
        try {
            startForegroundWithMediaProjection()
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val projection = mpm.getMediaProjection(resultCode, data) ?: run {
                Log.e(TAG, "MediaProjection 取得失敗")
                return
            }
            projection.registerCallback(mediaProjectionCallback, handler)
            val metrics = resources.displayMetrics
            val capture = ScreenCaptureManager(
                projection, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi
            )
            capture.start()
            mediaProjection = projection
            screenCapture = capture
            visionEnabled = true
            lastVisionAt = 0L  // 有効化直後の監視サイクルで画面コメントを出せるように
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_VISION_ENABLED, true).apply()
            handler.post { speechBubbleView?.showText(loc("画面が見えるようになったよ！👀", "I can see the screen now! 👀")) }
            Log.i(TAG, "Vision 有効化")
        } catch (e: Exception) {
            Log.e(TAG, "Vision 有効化失敗", e)
            disableVision()
        }
    }

    private fun disableVision() {
        screenCapture?.stop()
        screenCapture = null
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        if (visionEnabled) {
            visionEnabled = false
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_VISION_ENABLED, false).apply()
            // mediaProjection 型を外して specialUse のみで前景継続
            startForegroundCompat()
            Log.i(TAG, "Vision 無効化")
        }
    }

    private fun performChitchat(seed: String? = null, eventLabel: String? = null) {
        val gemma = gemmaManager ?: return
        if (!gemma.isReady()) return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val (comment, emotion) = if (seed != null) gemma.generateChitchat(seed) else gemma.generateChitchat()
            if (isShuttingDown) return@launch
            handler.post { showComment(comment, emotion) }
            memory.addEvent(if (eventLabel != null) "$eventLabel: $comment" else comment)
            memoryStore.save(memory)
            maybeConsolidate(gemma)
        }
    }

    /**
     * 時間帯に応じた能動的な声かけ（朝の挨拶・就寝リマインド）を定期チェックする。
     * 1分ごとに条件を評価し、各トリガーは1日1回まで（PREF で発火日を記録）。
     */
    private fun scheduleProactive() {
        proactiveRunnable?.let { handler.removeCallbacks(it) }
        proactiveRunnable = object : Runnable {
            override fun run() {
                checkProactiveTriggers()
                handler.postDelayed(this, PROACTIVE_CHECK_INTERVAL_MS)
            }
        }
        handler.postDelayed(proactiveRunnable!!, PROACTIVE_CHECK_INTERVAL_MS)
        Log.i(TAG, "プロアクティブ声かけ監視開始")
    }

    private fun checkProactiveTriggers() {
        val gemma = gemmaManager ?: return
        val idle = gemma.isReady() && !isShuttingDown && !gemma.isInferring() && dialogInputRoot == null
        if (!idle) return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

        when {
            hour in MORNING_START_HOUR..MORNING_END_HOUR &&
                prefs.getString(PREF_LAST_MORNING_DATE, "") != today -> {
                prefs.edit().putString(PREF_LAST_MORNING_DATE, today).apply()
                performChitchat(seed = buildMorningSeed(prefs, today), eventLabel = "朝の挨拶")
            }
            hour >= BEDTIME_START_HOUR &&
                prefs.getString(PREF_LAST_BEDTIME_DATE, "") != today -> {
                prefs.edit().putString(PREF_LAST_BEDTIME_DATE, today).apply()
                val seed = if (BuddyLanguage.isEnglish(prefs)) {
                    "It's late at night. Gently encourage the user to rest soon in one short line."
                } else {
                    "今は夜遅い時間。ユーザーが休めるよう、優しく就寝を促す一言を返して。"
                }
                performChitchat(seed = seed, eventLabel = "就寝リマインド")
            }
        }
    }

    /** 朝の挨拶用シード。週1回まで歩数の週次サマリーを添える。 */
    private fun buildMorningSeed(prefs: android.content.SharedPreferences, today: String): String {
        val en = BuddyLanguage.isEnglish(prefs)
        val base = if (en) "It's morning. Greet the user cheerfully and encourage them for the day in one short line."
            else "今は朝。ユーザーに明るく挨拶して、今日も一緒に頑張ろうと軽く声をかけて。"
        val weeklyDue = prefs.getString(PREF_LAST_WEEKLY_DATE, "") != currentWeekKey()
        if (!weeklyDue) return base
        val summary = memoryStore.weeklyStepSummary() ?: return base
        prefs.edit().putString(PREF_LAST_WEEKLY_DATE, currentWeekKey()).apply()
        return if (en) {
            base + "\nAlso share last week's step summary: total ${summary.total} steps over " +
                "${summary.daysWithData} days, avg ${summary.average}/day, best ${summary.bestSteps} on ${summary.bestDay}."
        } else {
            base + "\nまた、先週の歩数サマリーも教えてあげて: " +
                "直近${summary.daysWithData}日で合計${summary.total}歩、1日平均${summary.average}歩、" +
                "最高は${summary.bestDay}の${summary.bestSteps}歩。"
        }
    }

    /** 週の識別子（年-週番号）。週次サマリーを週1回に制限するため。 */
    private fun currentWeekKey(): String {
        val cal = java.util.Calendar.getInstance()
        val year = cal.get(java.util.Calendar.YEAR)
        val week = cal.get(java.util.Calendar.WEEK_OF_YEAR)
        return "$year-W$week"
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

        // 画面中央上部へスワイプして離す → バトル画面へ遷移
        characterView?.onDragReleaseListener = { rawX, rawY ->
            val metrics = resources.displayMetrics
            val inCenterX = rawX >= metrics.widthPixels * 0.30f && rawX <= metrics.widthPixels * 0.70f
            val inTop = rawY <= metrics.heightPixels * 0.25f
            if (!isShuttingDown && !isTransitioning && dialogInputRoot == null && inCenterX && inTop) {
                launchBattle()
            }
        }

        characterView?.onLongPressListener = {
            if (!isShuttingDown) {
                isShuttingDown = true
                monitorRunnable?.let { handler.removeCallbacks(it) }
                exitDialogMode()

                val en = BuddyLanguage.isEnglish(this)
                val farewell = farewells(en).randomOrNull() ?: if (en) "See you!" else "またね！"
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

        // シングルタップ → 対話モード開始
        characterView?.onTapListener = {
            when {
                gemmaManager?.isReady() != true -> {
                    speechBubbleView?.showText(loc("まだ読み込み中...もう少し待ってね！", "Still loading... just a bit more!"))
                    characterView?.setSpeaking(true)
                }
                isShuttingDown || dialogInputRoot != null -> { /* 無視 */ }
                else -> enterDialogMode()
            }
        }

        // ダブルタップ → 自由発話
        characterView?.onDoubleTapListener = {
            when {
                gemmaManager?.isReady() != true -> { /* 無視 */ }
                gemmaManager?.isInferring() == true -> { /* 無視 */ }
                else -> {
                    speechBubbleView?.showText(loc("考え中だよ〜！", "Thinking~!"))
                    characterView?.setSpeaking(true)
                    performChitchat()
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

    /** Buddy 発話の固定文言を会話言語で選ぶ（UI ロケールとは独立）。 */
    private fun loc(ja: String, en: String): String =
        if (BuddyLanguage.isEnglish(this)) en else ja

    /**
     * 全画面オーバーレイにレトロなワイプ演出（スパイラル渦 / アイリス）を出して暗転させ、
     * 完了後にバトル画面へ遷移する。パターンは毎回ランダム。
     */
    private fun launchBattle() {
        if (isTransitioning) return
        isTransitioning = true
        val pattern = BattleTransitionView.Pattern.values().random()

        val coverParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        val cover = FrameLayout(this)
        val transition = BattleTransitionView(this)
        val flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
        val mp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        cover.addView(transition, mp)
        cover.addView(flash, FrameLayout.LayoutParams(mp))
        coverRoot = cover
        try {
            windowManager.addView(cover, coverParams)
        } catch (e: Exception) {
            Log.e(TAG, "cover 追加失敗、直接遷移", e)
            coverRoot = null
            startBattleActivity(pattern)
            isTransitioning = false
            return
        }

        // エンカウント・フラッシュ ×2 ＋ 軽い画面シェイク
        flash.animate().alpha(0.9f).setDuration(70).withEndAction {
            flash.animate().alpha(0f).setDuration(70).withEndAction {
                flash.animate().alpha(0.7f).setDuration(70).withEndAction {
                    flash.animate().alpha(0f).setDuration(70).start()
                }.start()
            }.start()
        }.start()
        shakeView(cover)

        // フラッシュ後にワイプで暗転 → 遷移
        handler.postDelayed({
            transition.play(pattern, BattleTransitionView.Mode.COVER, 650L) {
                startBattleActivity(pattern)
                handler.postDelayed({ removeCover() }, 300)
                isTransitioning = false
            }
        }, 220)
    }

    private fun shakeView(v: View) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            addUpdateListener {
                val p = it.animatedValue as Float
                val amp = 20f * (1f - p)
                v.translationX = ((Math.random() - 0.5) * 2 * amp).toFloat()
                v.translationY = ((Math.random() - 0.5) * 2 * amp).toFloat()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { v.translationX = 0f; v.translationY = 0f }
            })
            start()
        }
    }

    private fun removeCover() {
        coverRoot?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        coverRoot = null
    }

    private fun startBattleActivity(pattern: BattleTransitionView.Pattern) {
        startActivity(Intent(this, BattleActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(BattleActivity.EXTRA_PATTERN, pattern.name)
        })
    }

    private fun showComment(text: String, emotion: BuddyEmotion = BuddyEmotion.NEUTRAL) {
        applyEmotion(emotion)
        speechBubbleView?.showText(text)
        characterView?.setSpeaking(true)
        // ウィジェット用に直近コメントを保存して更新を促す
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_LAST_COMMENT, text).apply()
        BuddyWidgetProvider.requestUpdate(this)
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
            speechBubbleView?.showText(loc("ん？", "Hmm?"))
            characterView?.setSpeaking(true)
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val (response, emotion) = gemma.generateResponse(eventText)
            if (isShuttingDown) return@launch
            memory.addEvent("反応: $response")
            memoryStore.save(memory)
            handler.post { showComment(response, emotion) }
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
                hideKeyboard(etInput)
                sendDialogMessage(msg)
            }
        }

        etInput.setOnEditorActionListener { _, _, _ ->
            val msg = etInput.text.toString().trim()
            if (msg.isNotEmpty() && gemmaManager?.isInferring() == false) {
                etInput.setText("")
                hideKeyboard(etInput)
                sendDialogMessage(msg)
            }
            true
        }

        etInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                handler.removeCallbacks(dialogTimeoutRunnable)
                handler.postDelayed(dialogTimeoutRunnable, DIALOG_TIMEOUT_MS)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        panelView.findViewById<Button>(R.id.btn_dialog_close).setOnClickListener {
            exitDialogMode()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        dialogInputRoot = panelView
        windowManager.addView(panelView, params)
        etInput.requestFocus()
        scheduleDialogTimeout()
        Log.i(TAG, "対話モード開始")
    }

    private fun hideKeyboard(view: android.view.View) {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun exitDialogMode() {
        handler.removeCallbacks(dialogTimeoutRunnable)
        dialogInputRoot?.let { root ->
            root.findViewById<EditText?>(R.id.et_dialog_input)?.let { hideKeyboard(it) }
            try { windowManager.removeView(root) } catch (_: Exception) {}
        }
        dialogInputRoot = null
        dialogTurnCount = 0
        handler.post {
            applyEmotion(BuddyEmotion.NEUTRAL)
            updateBubbleStyle()
            // 別れの挨拶は長押し退場時のみ（FAREWELL_COMMENTS）。対話終了では出さない。
        }
        Log.i(TAG, "対話モード終了")
    }

    private fun sendDialogMessage(message: String) {
        val gemma = gemmaManager ?: return
        handler.removeCallbacks(dialogTimeoutRunnable)
        handler.post {
            speechBubbleView?.showText(loc("考え中...", "Thinking..."))
            characterView?.setSpeaking(true)
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val (response, emotion) = gemma.generateResponse(message)
            if (isShuttingDown) return@launch
            dialogTurnCount++
            memory.addEvent("対話: $message → $response")
            // 「覚えておいて」等のフレーズで大切な思い出として恒久保存する
            maybeBookmarkMemory(message)
            memoryStore.save(memory)
            handler.post {
                showComment(response, emotion)
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

    /**
     * ユーザー発話に記憶キーワードが含まれていれば、その内容を
     * 「大切な思い出」として恒久保存する（プロファイルリセット後も残る）。
     */
    private fun maybeBookmarkMemory(userMessage: String) {
        if (BOOKMARK_KEYWORDS.none { userMessage.contains(it) }) return
        // キーワード前後を含むユーザー発話そのものを思い出として保存
        memoryStore.addImportantMemory(userMessage)
        Log.i(TAG, "大切な思い出として保存: $userMessage")
    }

    private fun buildNotification(): Notification {
        val channelId = "gemmabuddy_channel"
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val contentText = if (visionEnabled) "画面を見て話しかけてるよ（タップで会話）" else "タップして話しかけてね"
        return Notification.Builder(this, channelId)
            .setContentTitle("GemmaBuddy 稼働中")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** mediaProjection 型を含めて前景サービスを開始し直す（getMediaProjection の前に必須）。 */
    private fun startForegroundWithMediaProjection() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        isShuttingDown = true
        handler.removeCallbacksAndMessages(null)
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        stepManager?.stop()
        screenCapture?.stop()
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection?.stop()
        removeCover()
        unregisterReceiver(characterReloadReceiver)
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
        const val EXTRA_MODEL_PATH = "model_path"
        const val PREFS_NAME = "gemmabuddy_prefs"
        const val PREF_INTERVAL_MS = "interval_ms"
        const val DEFAULT_INTERVAL_MS = 5 * 60 * 1000L
        const val ACTION_ENABLE_VISION = "com.example.gemmabuddy.ACTION_ENABLE_VISION"
        const val ACTION_DISABLE_VISION = "com.example.gemmabuddy.ACTION_DISABLE_VISION"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val PREF_VISION_ENABLED = "vision_enabled"
        const val PREF_VISION_INTERVAL_MS = "vision_interval_ms"
        const val DEFAULT_VISION_INTERVAL_MS = 10 * 60 * 1000L
        private const val NOTIFICATION_ID = 1001
        const val ACTION_RELOAD_CHARACTER = "com.example.gemmabuddy.ACTION_RELOAD_CHARACTER"
        const val ACTION_RELOAD_PERSONALITY = "com.example.gemmabuddy.ACTION_RELOAD_PERSONALITY"
        const val ACTION_RESET_MEMORY = "com.example.gemmabuddy.ACTION_RESET_MEMORY"
        const val ACTION_HIDE_CHARACTER = "com.example.gemmabuddy.ACTION_HIDE_CHARACTER"
        const val ACTION_SHOW_CHARACTER = "com.example.gemmabuddy.ACTION_SHOW_CHARACTER"
        const val CUSTOM_CHAR_FILE = "custom_character.png"
        const val PREF_STEP_MODE = "step_mode"
        const val PREF_LAST_COMMENT = "last_comment"
        private const val PREF_LAST_MODEL_PATH = "last_model_path"

        private const val DIALOG_MAX_TURNS = 5
        private const val DIALOG_TIMEOUT_MS = 120_000L
        private const val REACTION_COOLDOWN_MS = 60_000L

        private const val PROACTIVE_CHECK_INTERVAL_MS = 60_000L
        private const val MORNING_START_HOUR = 7
        private const val MORNING_END_HOUR = 9
        private const val BEDTIME_START_HOUR = 23
        private const val PREF_LAST_MORNING_DATE = "last_morning_date"
        private const val PREF_LAST_BEDTIME_DATE = "last_bedtime_date"
        private const val PREF_LAST_WEEKLY_DATE = "last_weekly_date"

        private val BOOKMARK_KEYWORDS = listOf("覚えておいて", "覚えてて", "忘れないで", "記憶して")

        private val STEP_MILESTONES_JA = mapOf(
            500    to "500歩歩いたね！いいペース！",
            1000   to "1000歩突破！すごい！",
            3000   to "3000歩！調子いいじゃん！",
            5000   to "5000歩達成！半分来たね！",
            10000  to "1万歩！今日は最高だよ！"
        )
        private val STEP_MILESTONES_EN = mapOf(
            500    to "500 steps! Nice pace!",
            1000   to "Over 1000 steps! Amazing!",
            3000   to "3000 steps! You're on a roll!",
            5000   to "5000 steps! Halfway there!",
            10000  to "10,000 steps! Today's the best!"
        )
        fun stepMilestones(en: Boolean) = if (en) STEP_MILESTONES_EN else STEP_MILESTONES_JA

        private val FAREWELL_COMMENTS_JA = listOf(
            "おやすみ〜！またね♪",
            "じゃあね！またすぐ来てね！",
            "バイバイ！寂しいな〜",
            "またね！待ってるよ〜！",
            "おやすみなさい...ゆっくり休んでね",
            "またね！次も一緒に頑張ろうね♪",
            "バイバーイ！元気でね！"
        )
        private val FAREWELL_COMMENTS_EN = listOf(
            "Good night! See you~",
            "Bye! Come back soon!",
            "Bye-bye! I'll miss you~",
            "See you! I'll be waiting!",
            "Good night... rest well",
            "See you! Let's do our best again!",
            "Byee! Take care!"
        )
        fun farewells(en: Boolean) = if (en) FAREWELL_COMMENTS_EN else FAREWELL_COMMENTS_JA

    }
}

package com.example.gemmabuddy

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class GemmaManager(private val context: Context) {

    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    @Volatile private var inferring = false
    @Volatile private var currentMemoryDigest: String = ""
    @Volatile private var closeRequested = false

    suspend fun initialize(
        modelPath: String,
        memoryDigest: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                maxNumImages = 1
            )
            val eng = Engine(engineConfig)
            eng.initialize()
            // ネイティブ初期化完了後に終了要求を確認
            if (closeRequested) {
                try { eng.close() } catch (_: Exception) {}
                return@withContext false
            }
            engine = eng
            currentMemoryDigest = memoryDigest
            conversation = createConversationWithDigest(eng, memoryDigest)
            if (closeRequested) { doClose(); return@withContext false }
            if (conversation == null) {
                Log.e(TAG, "createConversation returned null")
                return@withContext false
            }
            Log.i(TAG, "LiteRT-LM 初期化完了: $modelPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "初期化失敗", e)
            false
        }
    }

    /**
     * 記憶ダイジェストを差し替えて Conversation を再構築する。
     * Engine は使い回す（モデルの再ロードは不要）。
     */
    fun rebuildConversation(memoryDigest: String, force: Boolean = false) {
        val eng = engine ?: return
        if (!force && memoryDigest == currentMemoryDigest) return
        try {
            conversation?.close()
            conversation = createConversationWithDigest(eng, memoryDigest)
            currentMemoryDigest = memoryDigest
            Log.i(TAG, "Conversation 再構築完了（force=$force）")
        } catch (e: Exception) {
            Log.e(TAG, "Conversation 再構築失敗", e)
        }
    }

    private fun prefs() =
        context.getSharedPreferences(OverlayService.PREFS_NAME, android.content.Context.MODE_PRIVATE)

    private fun isEnglish() = BuddyLanguage.isEnglish(prefs())

    private fun defaultChitchatSeed() =
        if (isEnglish()) "Say one short, friendly hello to the user."
        else "ユーザーに気軽に一言話しかけて。"

    private fun defaultVisionPrompt() =
        if (isEnglish()) "Look at this screen and give one short, charming comment in English."
        else "この画面を見て、短く愛嬌のある日本語コメントを1文だけしてください。"

    private fun createConversationWithDigest(
        eng: Engine,
        memoryDigest: String
    ): com.google.ai.edge.litertlm.Conversation? {
        val prefs = prefs()
        val personality = BuddyPersonality.load(prefs)
        val emotionRule =
            "返答の最後に必ず[EMOTION:HAPPY|EXCITED|SAD|ANGRY|SLEEPY|NEUTRAL]のいずれか1つを付けること。例: 楽しそう！[EMOTION:HAPPY]"
        val base = if (BuddyLanguage.isEnglish(prefs)) {
            "You are an 8-bit character living on the user's phone screen. " +
                "Reply with one short, friendly comment in English.\n" +
                personality.prompt + "\n" +
                "Always append exactly one [EMOTION:HAPPY|EXCITED|SAD|ANGRY|SLEEPY|NEUTRAL] tag at the very end. e.g. Looks fun! [EMOTION:HAPPY]"
        } else {
            "あなたはスマホ画面上に常駐する8bitキャラクターです。" +
                "ユーザーに気軽に話しかける短い日本語コメントを1文だけ返してください。\n" +
                personality.prompt + "\n" + emotionRule
        }
        val full = if (memoryDigest.isBlank()) base else "$base\n\n$memoryDigest"
        val convConfig = ConversationConfig(systemInstruction = Contents.of(full))
        return eng.createConversation(convConfig)
    }

    private fun parseEmotionTag(raw: String): Pair<String, BuddyEmotion> {
        val regex = Regex("""\[EMOTION:(\w+)\]""")
        val match = regex.find(raw)
        val emotion = match?.groupValues?.getOrNull(1)?.let { BuddyEmotion.fromTag(it) } ?: BuddyEmotion.NEUTRAL
        val text = raw.replace(regex, "").trim()
        return Pair(text, emotion)
    }

    /**
     * 会話履歴をクリアするため Conversation を閉じて即再作成する。
     * LiteRT-LM は1エンジンで同時に複数の Conversation を持てないため、
     * 都度クリアして単一 Conversation を使い回す方式を採用する。
     */
    private fun resetConversation() {
        val eng = engine ?: return
        try {
            conversation?.close()
            conversation = null
            conversation = createConversationWithDigest(eng, currentMemoryDigest)
            Log.d(TAG, "Conversation リセット完了")
        } catch (e: Exception) {
            Log.e(TAG, "Conversation リセット失敗", e)
        }
    }

    suspend fun generateResponse(userMessage: String): Pair<String, BuddyEmotion> = withContext(Dispatchers.IO) {
        inferring = true
        val conv = conversation
        if (conv == null || closeRequested) {
            inferring = false
            return@withContext Pair("モデルが読み込まれていません。", BuddyEmotion.NEUTRAL)
        }
        try {
            val sb = StringBuilder()
            conv.sendMessageAsync(userMessage).collect { chunk -> sb.append(chunk) }
            parseEmotionTag(sb.toString().trim().ifEmpty { "うーん..." })
        } catch (e: Exception) {
            Log.e(TAG, "対話生成エラー", e)
            Pair("ちょっと考え中〜", BuddyEmotion.NEUTRAL)
        } finally {
            inferring = false
            if (closeRequested) doClose()
        }
    }

    /**
     * 自発的な独り言を生成する。
     * [seed] に文脈（朝の挨拶・歩数サマリーなど）を渡すとそれを踏まえたコメントになる。
     * 既定は従来どおりの気軽な一言。
     */
    suspend fun generateChitchat(
        seed: String? = null
    ): Pair<String, BuddyEmotion> = withContext(Dispatchers.IO) {
        inferring = true
        val conv = conversation
        if (conv == null || closeRequested) {
            inferring = false
            return@withContext Pair("モデルが読み込まれていません。", BuddyEmotion.NEUTRAL)
        }
        try {
            val sb = StringBuilder()
            conv.sendMessageAsync(seed ?: defaultChitchatSeed()).collect { chunk -> sb.append(chunk) }
            val fallback = if (isEnglish()) "Hi!" else "やあ！"
            parseEmotionTag(sb.toString().trim().ifEmpty { fallback })
        } catch (e: Exception) {
            Log.e(TAG, "雑談生成エラー", e)
            Pair(if (isEnglish()) "Let me think~" else "ちょっと考え中〜", BuddyEmotion.NEUTRAL)
        } finally {
            inferring = false
            if (closeRequested) doClose()
        }
    }

    /**
     * 画面キャプチャ画像を見てコメントを生成する（Vision 推論）。
     * 画像は長辺 [VISION_MAX_EDGE_PX] px に縮小し JPEG 品質 [VISION_JPEG_QUALITY] で渡す。
     * 推論は約 15 秒かかるため、呼び出し側で多重実行と頻度を制御すること。
     */
    suspend fun generateCommentForScreen(
        bitmap: Bitmap,
        prompt: String? = null
    ): Pair<String, BuddyEmotion> = withContext(Dispatchers.IO) {
        inferring = true
        val conv = conversation
        if (conv == null || closeRequested) {
            inferring = false
            return@withContext Pair("モデルが読み込まれていません。", BuddyEmotion.NEUTRAL)
        }
        try {
            val jpegBytes = encodeToJpeg(bitmap)
            val sb = StringBuilder()
            conv.sendMessageAsync(
                Contents.of(Content.ImageBytes(jpegBytes), Content.Text(prompt ?: defaultVisionPrompt()))
            ).collect { chunk -> sb.append(chunk) }
            parseEmotionTag(sb.toString().trim().ifEmpty { if (isEnglish()) "I see~" else "なるほど〜" })
        } catch (e: Exception) {
            Log.e(TAG, "画面コメント生成エラー", e)
            Pair(if (isEnglish()) "Let me think~" else "ちょっと考え中〜", BuddyEmotion.NEUTRAL)
        } finally {
            inferring = false
            if (closeRequested) doClose()
        }
    }

    /** Bitmap を長辺 [VISION_MAX_EDGE_PX] px に縮小して JPEG エンコードする。 */
    private fun encodeToJpeg(src: Bitmap): ByteArray {
        val longEdge = maxOf(src.width, src.height)
        val scaled = if (longEdge > VISION_MAX_EDGE_PX) {
            val ratio = VISION_MAX_EDGE_PX.toFloat() / longEdge
            Bitmap.createScaledBitmap(
                src,
                (src.width * ratio).toInt().coerceAtLeast(1),
                (src.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            src
        }
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, VISION_JPEG_QUALITY, out)
            if (scaled !== src) scaled.recycle()
            out.toByteArray()
        }
    }

    /** Consolidation 用の単発推論。コメント生成と分離した独立コール。 */
    suspend fun consolidationRequest(prompt: String): String = withContext(Dispatchers.IO) {
        resetConversation()
        val conv = conversation ?: return@withContext ""
        try {
            val sb = StringBuilder()
            conv.sendMessageAsync(prompt).collect { chunk -> sb.append(chunk) }
            sb.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Consolidation 推論失敗", e)
            ""
        }
    }

    fun isInferring() = inferring

    fun isReady() = conversation != null

    fun close() {
        closeRequested = true
        // inferring=true なら generateComment の finally が doClose() を呼ぶ
        // inferring=false なら今すぐ安全に閉じられる
        if (!inferring) doClose()
    }

    private fun doClose() {
        conversation?.close()
        conversation = null
        val eng = engine
        engine = null
        eng?.close()
    }

    companion object {
        private const val TAG = "GemmaManager"
        private const val VISION_MAX_EDGE_PX = 512
        private const val VISION_JPEG_QUALITY = 85
    }
}

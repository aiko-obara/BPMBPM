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

    private fun createConversationWithDigest(
        eng: Engine,
        memoryDigest: String
    ): com.google.ai.edge.litertlm.Conversation? {
        val prefs = context.getSharedPreferences(OverlayService.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val personality = BuddyPersonality.load(prefs)
        val base = "あなたは画面上の8bitキャラクターです。ユーザーの画面を見て、" +
            "画面の内容に触れながら短く愛嬌のある日本語コメントを1文だけしてください。\n" +
            personality.prompt + "\n" +
            "返答の最後に必ず[EMOTION:HAPPY|EXCITED|SAD|ANGRY|SLEEPY|NEUTRAL]のいずれか1つを付けること。例: 楽しそう！[EMOTION:HAPPY]"
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

    suspend fun generateChitchat(): Pair<String, BuddyEmotion> = withContext(Dispatchers.IO) {
        inferring = true
        val conv = conversation
        if (conv == null || closeRequested) {
            inferring = false
            return@withContext Pair("モデルが読み込まれていません。", BuddyEmotion.NEUTRAL)
        }
        try {
            val sb = StringBuilder()
            conv.sendMessageAsync("今は画面を見ていない。ユーザーに気軽に一言話しかけて。").collect { chunk -> sb.append(chunk) }
            parseEmotionTag(sb.toString().trim().ifEmpty { "やあ！" })
        } catch (e: Exception) {
            Log.e(TAG, "雑談生成エラー", e)
            Pair("ちょっと考え中〜", BuddyEmotion.NEUTRAL)
        } finally {
            inferring = false
            if (closeRequested) doClose()
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

    suspend fun generateComment(screenshot: Bitmap? = null): Pair<String, BuddyEmotion> = withContext(Dispatchers.IO) {
        inferring = true
        val conv = conversation
        if (conv == null || closeRequested) {
            inferring = false
            return@withContext Pair("モデルが読み込まれていません。", BuddyEmotion.NEUTRAL)
        }
        try {
            Log.i(TAG, "推論開始")
            val sb = StringBuilder()
            if (screenshot != null) {
                val scaled = scaleBitmap(screenshot, 512)
                val bos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                val contents = Contents.of(
                    Content.ImageBytes(bos.toByteArray()),
                    Content.Text("この画面を見て、短く愛嬌のある日本語コメントを1文だけしてください。")
                )
                conv.sendMessageAsync(contents).collect { chunk -> sb.append(chunk) }
            } else {
                conv.sendMessageAsync("一言コメントして！").collect { chunk -> sb.append(chunk) }
            }
            val raw = sb.toString().trim()
            Log.i(TAG, "推論完了: $raw")
            parseEmotionTag(raw.ifEmpty { "やあ！なんか用？" })
        } catch (e: Exception) {
            Log.e(TAG, "推論エラー", e)
            Pair("画面を見ているよ〜！", BuddyEmotion.NEUTRAL)
        } finally {
            inferring = false
            if (closeRequested) doClose()
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val scale = minOf(maxSize.toFloat() / w, maxSize.toFloat() / h)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }

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
    }
}

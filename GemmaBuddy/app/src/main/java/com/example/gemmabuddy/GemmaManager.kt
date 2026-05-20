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

    suspend fun initialize(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                maxNumImages = 1
            )
            val eng = Engine(engineConfig)
            eng.initialize()

            val convConfig = ConversationConfig(
                systemInstruction = Contents.of(
                    "あなたは画面上の8bitキャラクターです。ユーザーの画面を見て、画面の内容に触れながら短く愛嬌のある日本語コメントを1文だけしてください。"
                )
            )
            engine = eng
            conversation = eng.createConversation(convConfig)
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

    fun isInferring() = inferring

    /**
     * テキスト記述からキャラクター各部位の色（パレットインデックス）を抽出する。
     * 専用の system instruction で fresh Conversation を作り、既存の「画面キャラ」コンテキストを排除。
     */
    suspend fun extractCharacterColors(
        description: String,
        type: TemplateType = TemplateType.HUMAN
    ): String = withContext(Dispatchers.IO) {
        val conv = conversation ?: return@withContext ""
        try {
            val prompt = CharacterTemplate.colorPromptFor(type) +
                "\nDescription: \"$description\"\n"
            val sb = StringBuilder()
            conv.sendMessageAsync(prompt).collect { chunk -> sb.append(chunk) }
            sb.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "色抽出失敗", e)
            ""
        }
    }

    suspend fun generateComment(screenshot: Bitmap? = null): String = withContext(Dispatchers.IO) {
        val conv = conversation ?: return@withContext "モデルが読み込まれていません。"
        inferring = true
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
            val result = sb.toString().trim()
            Log.i(TAG, "推論完了: $result")
            result.ifEmpty { "やあ！なんか用？" }
        } catch (e: Exception) {
            Log.e(TAG, "推論エラー", e)
            "画面を見ているよ〜！"
        } finally {
            inferring = false
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
        conversation?.close()
        engine?.close()
        conversation = null
        engine = null
    }

    companion object {
        private const val TAG = "GemmaManager"
    }
}

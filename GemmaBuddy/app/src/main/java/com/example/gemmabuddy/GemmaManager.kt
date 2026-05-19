package com.example.gemmabuddy

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GemmaManager(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null

    suspend fun initialize(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU()
            )
            val eng = Engine(engineConfig)
            eng.initialize()

            val convConfig = ConversationConfig(
                systemInstruction = Contents.of(
                    "あなたは画面上の8bitキャラクターです。ユーザーに短く愛嬌のある日本語コメントを1文だけしてください。"
                )
            )
            engine = eng
            conversation = eng.createConversation(convConfig)
            Log.i(TAG, "LiteRT-LM 初期化完了: $modelPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "初期化失敗", e)
            false
        }
    }

    suspend fun generateComment(): String = withContext(Dispatchers.IO) {
        val conv = conversation ?: return@withContext "モデルが読み込まれていません。"
        try {
            Log.i(TAG, "推論開始")
            val sb = StringBuilder()
            conv.sendMessageAsync("一言コメントして！").collect { chunk ->
                sb.append(chunk)
            }
            val result = sb.toString().trim()
            Log.i(TAG, "推論完了: $result")
            result.ifEmpty { "やあ！なんか用？" }
        } catch (e: Exception) {
            Log.e(TAG, "推論エラー", e)
            "画面を見ているよ〜！"
        }
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

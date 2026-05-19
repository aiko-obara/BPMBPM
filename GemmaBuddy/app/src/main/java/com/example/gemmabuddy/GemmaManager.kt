package com.example.gemmabuddy

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GemmaManager(private val context: Context) {

    private var llmInference: LlmInference? = null

    suspend fun initialize(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(256)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            Log.i(TAG, "Gemma4モデルロード完了: $modelPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Gemma4モデルロード失敗", e)
            false
        }
    }

    suspend fun generateComment(): String = withContext(Dispatchers.IO) {
        val inference = llmInference ?: return@withContext "モデルが読み込まれていません。"
        try {
            val prompt = buildPrompt()
            Log.i(TAG, "推論開始")
            val result = inference.generateResponse(prompt)
            Log.i(TAG, "推論完了: $result")
            result.trim().ifEmpty { "やあ！なんか用？" }
        } catch (e: Exception) {
            Log.e(TAG, "推論エラー", e)
            "画面を見ているよ〜！"
        }
    }

    private fun buildPrompt(): String {
        return "<start_of_turn>user\nあなたは画面に住む小さな8bitキャラクターです。ユーザーに一言、短く愛嬌のある日本語コメントをしてください。1文だけ。<end_of_turn>\n<start_of_turn>model\n"
    }

    fun isReady() = llmInference != null

    fun close() {
        llmInference?.close()
        llmInference = null
    }

    companion object {
        private const val TAG = "GemmaManager"
    }
}

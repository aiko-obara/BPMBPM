package com.example.gemmabuddy

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class GemmaManager(private val context: Context) {

    private var llmInference: LlmInference? = null

    suspend fun initialize(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(512)
                .setTopK(40)
                .setTemperature(0.8f)
                .setRandomSeed(42)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            Log.i(TAG, "Gemma4モデルロード完了: $modelPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Gemma4モデルロード失敗", e)
            false
        }
    }

    suspend fun analyzeScreen(screenshot: Bitmap): String = withContext(Dispatchers.Default) {
        val inference = llmInference ?: return@withContext "モデルが読み込まれていません。"
        try {
            val imageBase64 = bitmapToBase64(screenshot)
            val prompt = buildPrompt(imageBase64)
            withContext(Dispatchers.IO) {
                inference.generateResponse(prompt)
            }
        } catch (e: Exception) {
            Log.e(TAG, "推論エラー", e)
            "画面を見ているよ〜！"
        }
    }

    private fun buildPrompt(imageBase64: String): String {
        return """あなたは画面上に住む8bitキャラクターです。
ユーザーのスマートフォン画面を見ています。
画面の様子を元に、短くて愛嬌のあるコメントを1〜2文で日本語で答えてください。
ユーモラスで親しみやすい口調で。絵文字は使わず、シンプルに。

[画面データ(base64)]
${imageBase64.take(2000)}

キャラクターのコメント:"""
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, 512, 512 * bitmap.height / bitmap.width, true)
        val outputStream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
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

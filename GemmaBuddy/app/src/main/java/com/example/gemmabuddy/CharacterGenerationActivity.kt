package com.example.gemmabuddy

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * オンデバイス Gemma 4 を使って、テキスト記述から 32×32 ピクセルキャラを生成する画面。
 *
 * フロー:
 *   1. ユーザーがキャラ説明を入力（例: ピンク髪の魔法使い）
 *   2. Gemma 4 が各部位の色（HAIR/FACE/EYE/MOUTH/SHIRT/ARM/HAND/PANTS/FOOT）を抽出
 *   3. 固定テンプレート（目・手・足が明確）に色を流し込み 32×32 Bitmap 生成
 *   4. 256×256 に拡大してプレビュー
 *   5. OK なら custom_character.png に保存
 */
class CharacterGenerationActivity : AppCompatActivity() {

    private lateinit var rgTemplate: RadioGroup
    private lateinit var etDescription: EditText
    private lateinit var btnGenerate: Button
    private lateinit var layoutProgress: LinearLayout
    private lateinit var tvProgressStatus: TextView
    private lateinit var layoutPreview: LinearLayout
    private lateinit var ivGeneratedChar: ImageView
    private lateinit var btnUseCharacter: Button
    private lateinit var btnRegenerate: Button

    private var generatedBitmap: Bitmap? = null
    private var gemmaManager: GemmaManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.WHITE
        setContentView(R.layout.activity_character_generation)

        rgTemplate = findViewById(R.id.rg_template)
        etDescription = findViewById(R.id.et_description)
        btnGenerate = findViewById(R.id.btn_generate)
        layoutProgress = findViewById(R.id.layout_progress)
        tvProgressStatus = findViewById(R.id.tv_progress_status)
        layoutPreview = findViewById(R.id.layout_preview)
        ivGeneratedChar = findViewById(R.id.iv_generated_char)
        btnUseCharacter = findViewById(R.id.btn_use_character)
        btnRegenerate = findViewById(R.id.btn_regenerate)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        btnGenerate.setOnClickListener { startGeneration() }
        btnUseCharacter.setOnClickListener { saveAndUseCharacter() }
        btnRegenerate.setOnClickListener {
            layoutPreview.visibility = View.GONE
            startGeneration()
        }
    }

    override fun onDestroy() {
        gemmaManager?.close()
        gemmaManager = null
        super.onDestroy()
    }

    private fun startGeneration() {
        val description = etDescription.text.toString().trim()
        if (description.isBlank()) {
            Toast.makeText(this, "キャラクターの説明を入力してください", Toast.LENGTH_SHORT).show()
            return
        }
        setGenerating(true)

        lifecycleScope.launch {
            try {
                tvProgressStatus.text = "モデルを準備中..."
                val gemma = ensureGemma()
                if (gemma == null) {
                    Toast.makeText(this@CharacterGenerationActivity,
                        "Gemma モデルが見つかりません。先にモデルをダウンロードしてください",
                        Toast.LENGTH_LONG).show()
                    setGenerating(false)
                    return@launch
                }

                tvProgressStatus.text = "色を選んでいます..."
                val selectedType = selectedTemplateType()
                val colorText = gemma.extractCharacterColors(description, selectedType)
                Log.i(TAG, "Gemma 出力:\n$colorText")
                if (colorText.isBlank()) throw IllegalStateException("Gemma の出力が空でした")

                val (raw, upscaled) = withContext(Dispatchers.Default) {
                    val colors = CharacterTemplate.parseColors(colorText)
                    Log.i(TAG, "パース結果: $colors")
                    val r = CharacterTemplate.render(colors, selectedType)
                    val u = PixelGridParser.upscale(r, 256)
                    r to u
                }

                generatedBitmap = raw
                ivGeneratedChar.setImageBitmap(upscaled)
                layoutPreview.visibility = View.VISIBLE
                setGenerating(false)
            } catch (e: Exception) {
                Log.e(TAG, "生成失敗", e)
                setGenerating(false)
                Toast.makeText(this@CharacterGenerationActivity,
                    "生成に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun ensureGemma(): GemmaManager? = withContext(Dispatchers.IO) {
        val existing = gemmaManager
        if (existing != null && existing.isReady()) return@withContext existing
        val modelPath = ModelDownloadManager(this@CharacterGenerationActivity).getModelPath()
            ?: return@withContext null
        val gm = existing ?: GemmaManager(this@CharacterGenerationActivity).also {
            gemmaManager = it
        }
        val ok = gm.initialize(modelPath)
        if (ok) gm else null
    }

    private fun saveAndUseCharacter() {
        val bitmap = generatedBitmap ?: return
        try {
            val upscaled = PixelGridParser.upscale(bitmap, 256)
            val file = File(filesDir, OverlayService.CUSTOM_CHAR_FILE)
            FileOutputStream(file).use { out ->
                upscaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            sendBroadcast(Intent(OverlayService.ACTION_RELOAD_CHARACTER))
            Toast.makeText(this, "キャラクターを保存しました！", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "保存失敗", e)
            Toast.makeText(this, "保存に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectedTemplateType(): TemplateType = when (rgTemplate.checkedRadioButtonId) {
        R.id.rb_animal -> TemplateType.ANIMAL
        R.id.rb_robot -> TemplateType.ROBOT
        R.id.rb_dragon -> TemplateType.DRAGON
        else -> TemplateType.HUMAN
    }

    private fun setGenerating(isGenerating: Boolean) {
        layoutProgress.visibility = if (isGenerating) View.VISIBLE else View.GONE
        btnGenerate.isEnabled = !isGenerating
        etDescription.isEnabled = !isGenerating
    }

    companion object {
        private const val TAG = "CharacterGenerationActivity"
    }
}

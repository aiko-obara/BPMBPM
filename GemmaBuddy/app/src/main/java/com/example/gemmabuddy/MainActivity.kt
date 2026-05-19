package com.example.gemmabuddy

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.gemmabuddy.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var modelDownloadManager: ModelDownloadManager

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateUI() }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { startOverlayService(it) }
        } else {
            Toast.makeText(this, "画面キャプチャが拒否されました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        modelDownloadManager = ModelDownloadManager(this)
        setupButtons()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun setupButtons() {
        binding.btnOverlayPermission.setOnClickListener {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        binding.btnDownloadModel.setOnClickListener {
            startModelDownload()
        }

        binding.btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "オーバーレイ権限が必要です", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!modelDownloadManager.isModelAvailable()) {
                Toast.makeText(this, "モデルをダウンロードしてください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requestScreenCapture()
        }

        binding.btnStop.setOnClickListener {
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_STOP
            })
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun startModelDownload() {
        binding.btnDownloadModel.isEnabled = false
        binding.downloadProgress.visibility = View.VISIBLE
        binding.tvModelStatus.text = "ダウンロード中..."

        modelDownloadManager.downloadModel(
            onProgress = { progress ->
                runOnUiThread {
                    binding.downloadProgress.progress = (progress * 100).toInt()
                    binding.tvModelStatus.text = "ダウンロード中... ${(progress * 100).toInt()}%"
                }
            },
            onSuccess = {
                runOnUiThread {
                    binding.downloadProgress.visibility = View.GONE
                    Toast.makeText(this, "モデルのダウンロード完了！", Toast.LENGTH_SHORT).show()
                    updateUI()
                }
            },
            onFailure = { msg ->
                runOnUiThread {
                    binding.downloadProgress.visibility = View.GONE
                    binding.btnDownloadModel.isEnabled = true
                    Toast.makeText(this, "ダウンロード失敗: $msg", Toast.LENGTH_LONG).show()
                    updateUI()
                }
            }
        )
    }

    private fun requestScreenCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun startOverlayService(projectionData: Intent) {
        val modelPath = modelDownloadManager.getModelPath() ?: return
        startForegroundService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_PROJECTION_DATA, projectionData)
            putExtra(OverlayService.EXTRA_MODEL_PATH, modelPath)
        })
        Toast.makeText(this, "GemmaBuddyを起動しました！", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val source = modelDownloadManager.getModelSource()
        val hasModel = source != ModelDownloadManager.ModelSource.NONE

        binding.btnOverlayPermission.isEnabled = !hasOverlay
        binding.tvOverlayStatus.text =
            if (hasOverlay) "✓ オーバーレイ権限: 付与済み" else "✗ オーバーレイ権限: 未付与（タップして設定へ）"

        binding.tvModelStatus.text = when (source) {
            ModelDownloadManager.ModelSource.AI_EDGE_GALLERY ->
                "✓ Gemma4モデル: AI Edge Galleryから検出"
            ModelDownloadManager.ModelSource.PLAY_ASSET_DELIVERY ->
                "✓ Gemma4モデル: ダウンロード済み"
            ModelDownloadManager.ModelSource.NONE ->
                "✗ Gemma4モデル: 未検出"
        }

        binding.btnDownloadModel.isEnabled = !hasModel
        binding.btnDownloadModel.text = when (source) {
            ModelDownloadManager.ModelSource.AI_EDGE_GALLERY -> "AI Edge Galleryを使用中"
            ModelDownloadManager.ModelSource.PLAY_ASSET_DELIVERY -> "ダウンロード済み"
            ModelDownloadManager.ModelSource.NONE -> "Gemma4をダウンロード（Play）"
        }
        binding.downloadProgress.visibility = View.GONE
        binding.btnStart.isEnabled = hasOverlay && hasModel
    }
}

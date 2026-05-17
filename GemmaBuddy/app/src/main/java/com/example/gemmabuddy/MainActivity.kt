package com.example.gemmabuddy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.gemmabuddy.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var modelPath: String? = null

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkPermissionsAndUpdateUI() }

    private val modelPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { importModel(it) }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            startOverlayService(data)
        } else {
            Toast.makeText(this, "画面キャプチャが拒否されました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSavedModel()
        setupButtons()
        checkPermissionsAndUpdateUI()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndUpdateUI()
    }

    private fun setupButtons() {
        binding.btnOverlayPermission.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }

        binding.btnSelectModel.setOnClickListener {
            modelPickerLauncher.launch("*/*")
        }

        binding.btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "オーバーレイ権限が必要です", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (modelPath == null) {
                Toast.makeText(this, "モデルファイルを選択してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requestScreenCapture()
        }

        binding.btnStop.setOnClickListener {
            stopOverlayService()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun requestScreenCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun startOverlayService(projectionData: Intent) {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_PROJECTION_DATA, projectionData)
            putExtra(OverlayService.EXTRA_MODEL_PATH, modelPath)
        }
        startForegroundService(intent)
        Toast.makeText(this, "GemmaBuddyを起動しました！", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(intent)
    }

    private fun importModel(uri: Uri) {
        val destDir = getExternalFilesDir("models") ?: filesDir
        val destFile = File(destDir, "gemma4.task")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            modelPath = destFile.absolutePath
            saveModelPath(modelPath!!)
            binding.tvModelStatus.text = "モデル: ${destFile.name}"
            Toast.makeText(this, "モデルをインポートしました", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "インポート失敗: ${e.message}", Toast.LENGTH_LONG).show()
        }
        checkPermissionsAndUpdateUI()
    }

    private fun loadSavedModel() {
        val prefs = getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString("model_path", null)
        if (saved != null && File(saved).exists()) {
            modelPath = saved
        }
    }

    private fun saveModelPath(path: String) {
        getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("model_path", path).apply()
    }

    private fun checkPermissionsAndUpdateUI() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasModel = modelPath != null

        binding.btnOverlayPermission.isEnabled = !hasOverlay
        binding.tvOverlayStatus.text = if (hasOverlay) "✓ オーバーレイ権限: 付与済み" else "✗ オーバーレイ権限: 未付与（タップして設定へ）"
        binding.tvModelStatus.text = if (hasModel) "✓ モデル: 設定済み" else "✗ モデル: 未選択"
        binding.btnStart.isEnabled = hasOverlay && hasModel
    }
}

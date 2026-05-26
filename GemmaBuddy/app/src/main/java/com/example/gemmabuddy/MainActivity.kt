package com.example.gemmabuddy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var modelDownloadManager: ModelDownloadManager

    // Views
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnOverlayPermission: Button
    private lateinit var btnDownloadModel: Button
    private lateinit var btnCopyModel: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var ivBuddyPreview: ImageView
    private lateinit var tvStatusText: TextView
    private lateinit var dotStatus: View
    private lateinit var tvInteractions: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvSteps: TextView

    companion object {
        private const val REQ_ACTIVITY_RECOGNITION = 101
    }

    private val startTime = System.currentTimeMillis()
    private val uptimeHandler = Handler(Looper.getMainLooper())

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateUI() }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        modelDownloadManager = ModelDownloadManager(this)
        bindViews()
        setupButtons()
        setupBottomNav(NavTab.HOME)
        updateUI()
        startUptimeCounter()
        requestActivityRecognitionPermission()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        loadBuddyPreview()
        loadActiveBuddyFromStore()
        refreshStepDisplay()
    }

    private fun loadActiveBuddyFromStore() {
        val store = BuddyStore(this)
        val active = store.getActive() ?: return
        val file = store.getFile(active)
        if (file.exists()) {
            val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            ivBuddyPreview.setImageBitmap(bmp)
            // 名前フィールドがあれば更新
            findViewById<TextView>(R.id.tv_buddy_name)?.text = active.name
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uptimeHandler.removeCallbacksAndMessages(null)
    }

    private fun bindViews() {
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnOverlayPermission = findViewById(R.id.btn_overlay_permission)
        btnDownloadModel = findViewById(R.id.btn_download_model)
        btnCopyModel = findViewById(R.id.btn_copy_model)
        progressBar = findViewById(R.id.download_progress)
        ivBuddyPreview = findViewById(R.id.iv_buddy_preview)
        tvStatusText = findViewById(R.id.tv_status_text)
        dotStatus = findViewById(R.id.dot_status)
        tvInteractions = findViewById(R.id.tv_interactions)
        tvUptime = findViewById(R.id.tv_uptime)
        tvSteps = findViewById(R.id.tv_steps)
    }

    private fun setupButtons() {
        btnOverlayPermission.setOnClickListener {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        btnCopyModel.setOnClickListener { startModelCopy() }
        btnDownloadModel.setOnClickListener { startModelDownload() }
        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "OVERLAY PERMISSION REQUIRED", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!modelDownloadManager.isModelAvailable()) {
                Toast.makeText(this, "AI MODEL NOT FOUND", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startOverlayService()
        }
        btnStop.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            updateStatusChip(false)
        }
    }

    private fun loadBuddyPreview() {
        lifecycleScope.launch {
            val file = File(filesDir, OverlayService.CUSTOM_CHAR_FILE)
            val bmp = withContext(Dispatchers.IO) {
                if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            }
            bmp?.let { ivBuddyPreview.setImageBitmap(it) }
                ?: ivBuddyPreview.setImageResource(android.R.drawable.ic_menu_camera)
            val memory = withContext(Dispatchers.IO) { MemoryStore(this@MainActivity).load() }
            tvInteractions.text = memory.recentEvents.size.toString()
        }
    }

    private fun startUptimeCounter() {
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val h = TimeUnit.MILLISECONDS.toHours(elapsed)
                val m = TimeUnit.MILLISECONDS.toMinutes(elapsed) % 60
                tvUptime.text = if (h > 0) "${h}h ${m}m" else "${m}m"
                uptimeHandler.postDelayed(this, 60_000)
            }
        }
        uptimeHandler.post(runnable)
    }

    private fun updateStatusChip(active: Boolean) {
        if (active) {
            tvStatusText.text = "BUDDY ACTIVE"
            dotStatus.setBackgroundColor(getColor(R.color.nr_cyber_green))
        } else {
            tvStatusText.text = "OFFLINE"
            dotStatus.setBackgroundColor(getColor(R.color.nr_on_surface_variant))
        }
    }

    private fun refreshStepDisplay() {
        val prefs = getSharedPreferences(StepCounterManager.PREFS_NAME, MODE_PRIVATE)
        val steps = prefs.getInt(StepCounterManager.PREF_TODAY_STEPS, 0)
        tvSteps.text = steps.toString()
    }

    private fun requestActivityRecognitionPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), REQ_ACTIVITY_RECOGNITION)
            }
        }
    }

    private fun startOverlayService() {
        val modelPath = modelDownloadManager.getModelPath() ?: return
        startForegroundService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_MODEL_PATH, modelPath)
        })
        updateStatusChip(true)
        Toast.makeText(this, "SESSION STARTED", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val source = modelDownloadManager.getModelSource()
        val hasModel = source == ModelDownloadManager.ModelSource.INTERNAL ||
            source == ModelDownloadManager.ModelSource.PLAY_ASSET_DELIVERY

        // Show permission button only if needed
        if (!hasOverlay) {
            btnOverlayPermission.visibility = View.VISIBLE
            btnOverlayPermission.text = "GRANT OVERLAY PERMISSION"
        } else {
            btnOverlayPermission.visibility = View.GONE
        }

        // Show model buttons only if needed
        when (source) {
            ModelDownloadManager.ModelSource.NONE -> {
                btnDownloadModel.visibility = View.VISIBLE
                btnDownloadModel.isEnabled = true
                btnDownloadModel.text = "DOWNLOAD AI MODEL"
                btnCopyModel.visibility = View.GONE
            }
            ModelDownloadManager.ModelSource.EXTERNAL_NEEDS_COPY -> {
                btnCopyModel.visibility = View.VISIBLE
                btnCopyModel.isEnabled = true
                btnDownloadModel.visibility = View.GONE
            }
            else -> {
                btnDownloadModel.visibility = View.GONE
                btnCopyModel.visibility = View.GONE
            }
        }

        btnStart.isEnabled = hasOverlay && hasModel
        loadBuddyPreview()
    }

    private fun startModelCopy() {
        btnCopyModel.isEnabled = false
        progressBar.visibility = View.VISIBLE
        modelDownloadManager.copyModelToInternalStorage(
            onProgress = { p -> runOnUiThread { progressBar.progress = (p * 100).toInt() } },
            onSuccess = {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "MODEL READY", Toast.LENGTH_SHORT).show()
                    updateUI()
                }
            },
            onFailure = { msg ->
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "COPY FAILED: $msg", Toast.LENGTH_LONG).show()
                    updateUI()
                }
            }
        )
    }

    private fun startModelDownload() {
        btnDownloadModel.isEnabled = false
        progressBar.visibility = View.VISIBLE
        modelDownloadManager.downloadModel(
            onProgress = { p -> runOnUiThread { progressBar.progress = (p * 100).toInt() } },
            onSuccess = {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "DOWNLOAD COMPLETE", Toast.LENGTH_SHORT).show()
                    updateUI()
                }
            },
            onFailure = { msg ->
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnDownloadModel.isEnabled = true
                    Toast.makeText(this, "DOWNLOAD FAILED: $msg", Toast.LENGTH_LONG).show()
                    updateUI()
                }
            }
        )
    }
}

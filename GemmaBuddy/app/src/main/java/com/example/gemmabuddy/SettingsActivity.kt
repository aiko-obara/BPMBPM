package com.example.gemmabuddy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy {
        getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Speed values (ms per char)
    private val SPEED_SLOW = 120L
    private val SPEED_NORMAL = 60L
    private val SPEED_FAST = 30L

    // Interval values (ms) — steps
    private val intervalSteps = listOf(
        30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 1_800_000L
    )

    private lateinit var btnSlow: Button
    private lateinit var btnNormal: Button
    private lateinit var btnFast: Button
    private lateinit var btnMinus: Button
    private lateinit var btnPlus: Button
    private lateinit var tvIntervalValue: TextView
    private lateinit var btnPurge: Button
    private lateinit var tvNotificationStatus: TextView
    private lateinit var btnNotificationAccess: Button
    private lateinit var btnStepModeBuddy: Button
    private lateinit var btnStepModeAlways: Button
    private lateinit var tvVisionStatus: TextView
    private lateinit var btnVisionToggle: Button
    private lateinit var tvUiLanguageCurrent: TextView
    private lateinit var btnUiLanguage: Button
    private lateinit var tvBuddyLanguageCurrent: TextView
    private lateinit var btnBuddyLanguage: Button

    private var currentIntervalIdx = 3  // default 5m

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svc = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_ENABLE_VISION
                putExtra(OverlayService.EXTRA_PROJECTION_RESULT_CODE, result.resultCode)
                putExtra(OverlayService.EXTRA_PROJECTION_DATA, result.data)
            }
            startService(svc)
            updateVisionUI(true)
            Toast.makeText(this, R.string.toast_vision_enabled, Toast.LENGTH_SHORT).show()
        } else {
            updateVisionUI(false)
            Toast.makeText(this, R.string.toast_vision_canceled, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setupBottomNav(NavTab.SETTINGS)

        bindViews()
        loadPrefs()
        setupListeners()
    }

    private fun bindViews() {
        btnSlow = findViewById(R.id.btn_speed_slow)
        btnNormal = findViewById(R.id.btn_speed_normal)
        btnFast = findViewById(R.id.btn_speed_fast)
        btnMinus = findViewById(R.id.btn_interval_minus)
        btnPlus = findViewById(R.id.btn_interval_plus)
        tvIntervalValue = findViewById(R.id.tv_interval_value)
        btnPurge = findViewById(R.id.btn_purge_data)
        tvNotificationStatus = findViewById(R.id.tv_notification_status)
        btnNotificationAccess = findViewById(R.id.btn_notification_access)
        btnStepModeBuddy = findViewById(R.id.btn_step_mode_buddy)
        btnStepModeAlways = findViewById(R.id.btn_step_mode_always)
        tvVisionStatus = findViewById(R.id.tv_vision_status)
        btnVisionToggle = findViewById(R.id.btn_vision_toggle)
        tvUiLanguageCurrent = findViewById(R.id.tv_ui_language_current)
        btnUiLanguage = findViewById(R.id.btn_ui_language)
        tvBuddyLanguageCurrent = findViewById(R.id.tv_buddy_language_current)
        btnBuddyLanguage = findViewById(R.id.btn_buddy_language)
    }

    private fun loadPrefs() {
        // Speed
        val speed = prefs.getString("typewriter_speed", "60")?.toLongOrNull() ?: 60L
        updateSpeedUI(speed)

        // Interval
        val intervalMs = prefs.getLong(OverlayService.PREF_INTERVAL_MS, OverlayService.DEFAULT_INTERVAL_MS)
        currentIntervalIdx = intervalSteps.indexOfFirst { it >= intervalMs }.coerceAtLeast(0)
        updateIntervalUI()

        // Notification access status
        updateNotificationStatusUI()

        // Step mode
        val stepMode = prefs.getString(OverlayService.PREF_STEP_MODE, "1") ?: "1"
        updateStepModeUI(stepMode)

        // Vision
        updateVisionUI(prefs.getBoolean(OverlayService.PREF_VISION_ENABLED, false))

        // Languages
        updateLanguageUI()
    }

    private fun setupListeners() {
        btnSlow.setOnClickListener { saveSpeed(SPEED_SLOW); updateSpeedUI(SPEED_SLOW) }
        btnNormal.setOnClickListener { saveSpeed(SPEED_NORMAL); updateSpeedUI(SPEED_NORMAL) }
        btnFast.setOnClickListener { saveSpeed(SPEED_FAST); updateSpeedUI(SPEED_FAST) }

        btnMinus.setOnClickListener {
            if (currentIntervalIdx > 0) {
                currentIntervalIdx--
                saveInterval()
                updateIntervalUI()
            }
        }
        btnPlus.setOnClickListener {
            if (currentIntervalIdx < intervalSteps.lastIndex) {
                currentIntervalIdx++
                saveInterval()
                updateIntervalUI()
            }
        }

        btnPurge.setOnClickListener { confirmPurge() }
        btnNotificationAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnStepModeBuddy.setOnClickListener { saveStepMode("1") }
        btnStepModeAlways.setOnClickListener { saveStepMode("2") }

        btnVisionToggle.setOnClickListener { toggleVision() }

        btnUiLanguage.setOnClickListener { showUiLanguagePicker() }
        btnBuddyLanguage.setOnClickListener { showBuddyLanguagePicker() }
    }

    // ─────────────────────────────────────────────
    // 言語設定（UI言語 = per-app locale / Buddy会話言語 = pref）
    // ─────────────────────────────────────────────

    private fun updateLanguageUI() {
        val locales = AppCompatDelegate.getApplicationLocales()
        tvUiLanguageCurrent.text = when (locales.toLanguageTags().lowercase()) {
            "" -> getString(R.string.lang_system)
            "ja" -> getString(R.string.lang_ja)
            "en" -> getString(R.string.lang_en)
            else -> locales.toLanguageTags()
        }
        val buddyEn = BuddyLanguage.isEnglish(prefs)
        tvBuddyLanguageCurrent.text = getString(if (buddyEn) R.string.lang_en else R.string.lang_ja)
    }

    private fun showUiLanguagePicker() {
        // index 0=system, 1=ja, 2=en
        val items = arrayOf(
            getString(R.string.lang_system),
            getString(R.string.lang_ja),
            getString(R.string.lang_en)
        )
        val current = when (AppCompatDelegate.getApplicationLocales().toLanguageTags().lowercase()) {
            "ja" -> 1
            "en" -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_ui_language_title)
            .setSingleChoiceItems(items, current) { dialog, which ->
                val locales = when (which) {
                    1 -> LocaleListCompat.forLanguageTags("ja")
                    2 -> LocaleListCompat.forLanguageTags("en")
                    else -> LocaleListCompat.getEmptyLocaleList()
                }
                AppCompatDelegate.setApplicationLocales(locales)
                dialog.dismiss()
                // setApplicationLocales が Activity を再生成して反映する
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showBuddyLanguagePicker() {
        // index 0=ja, 1=en
        val items = arrayOf(getString(R.string.lang_ja), getString(R.string.lang_en))
        val current = if (BuddyLanguage.isEnglish(prefs)) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_buddy_language_title)
            .setSingleChoiceItems(items, current) { dialog, which ->
                prefs.edit().putString(BuddyLanguage.PREF_KEY, if (which == 1) "en" else "ja").apply()
                updateLanguageUI()
                // 稼働中サービスのシステムプロンプトを再構築させる
                sendBroadcast(Intent(OverlayService.ACTION_RELOAD_PERSONALITY).apply {
                    `package` = packageName
                })
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun toggleVision() {
        val enabled = prefs.getBoolean(OverlayService.PREF_VISION_ENABLED, false)
        if (enabled) {
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_DISABLE_VISION
            })
            updateVisionUI(false)
            Toast.makeText(this, R.string.toast_vision_disabled, Toast.LENGTH_SHORT).show()
        } else {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun updateVisionUI(enabled: Boolean) {
        tvVisionStatus.text = getString(if (enabled) R.string.settings_vision_status_on else R.string.settings_vision_status_off)
        tvVisionStatus.setTextColor(
            if (enabled) getColor(R.color.nr_cyber_green) else getColor(R.color.nr_on_surface_variant)
        )
        btnVisionToggle.text = getString(if (enabled) R.string.settings_vision_disable else R.string.settings_vision_enable)
    }

    private fun saveSpeed(ms: Long) {
        prefs.edit().putString("typewriter_speed", ms.toString()).apply()
    }

    private fun saveInterval() {
        val ms = intervalSteps[currentIntervalIdx]
        prefs.edit().putLong(OverlayService.PREF_INTERVAL_MS, ms).apply()
    }

    private fun updateSpeedUI(speed: Long) {
        val selected = getColor(R.color.nr_primary)
        val unselected = getColor(R.color.nr_on_surface)
        btnSlow.setTextColor(if (speed == SPEED_SLOW) getColor(R.color.nr_on_primary) else unselected)
        btnNormal.setTextColor(if (speed == SPEED_NORMAL) getColor(R.color.nr_on_primary) else unselected)
        btnFast.setTextColor(if (speed == SPEED_FAST) getColor(R.color.nr_on_primary) else unselected)
        btnSlow.backgroundTintList = null
        btnNormal.backgroundTintList = null
        btnFast.backgroundTintList = null
        btnSlow.setBackgroundResource(if (speed == SPEED_SLOW) R.drawable.bg_segment_selected else R.drawable.bg_segment_unselected)
        btnNormal.setBackgroundResource(if (speed == SPEED_NORMAL) R.drawable.bg_segment_selected else R.drawable.bg_segment_unselected)
        btnFast.setBackgroundResource(if (speed == SPEED_FAST) R.drawable.bg_segment_selected else R.drawable.bg_segment_unselected)
    }

    private fun updateIntervalUI() {
        val ms = intervalSteps[currentIntervalIdx]
        tvIntervalValue.text = when {
            ms < 60_000 -> "${ms / 1000}s"
            ms < 3_600_000 -> "${ms / 60_000}m"
            else -> "${ms / 3_600_000}h"
        }
    }

    private fun saveStepMode(mode: String) {
        prefs.edit().putString(OverlayService.PREF_STEP_MODE, mode).apply()
        updateStepModeUI(mode)
        if (mode == "2") {
            startService(Intent(this, StepCounterService::class.java))
        } else {
            stopService(Intent(this, StepCounterService::class.java))
        }
        Toast.makeText(
            this,
            if (mode == "2") R.string.toast_step_always else R.string.toast_step_buddy,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateStepModeUI(mode: String) {
        val isBuddy = mode == "1"
        btnStepModeBuddy.setBackgroundResource(
            if (isBuddy) R.drawable.bg_dark_segment_selected else R.drawable.bg_dark_segment_unselected
        )
        btnStepModeAlways.setBackgroundResource(
            if (!isBuddy) R.drawable.bg_dark_segment_selected else R.drawable.bg_dark_segment_unselected
        )
        btnStepModeBuddy.setTextColor(
            if (isBuddy) getColor(R.color.nr_primary) else getColor(R.color.nr_on_surface_variant)
        )
        btnStepModeAlways.setTextColor(
            if (!isBuddy) getColor(R.color.nr_primary) else getColor(R.color.nr_on_surface_variant)
        )
    }

    private fun updateNotificationStatusUI() {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        tvNotificationStatus.text = getString(if (enabled) R.string.settings_notif_status_on else R.string.settings_notif_status_off)
        tvNotificationStatus.setTextColor(
            if (enabled) getColor(R.color.nr_cyber_green) else getColor(R.color.nr_on_surface_variant)
        )
    }

    private fun confirmPurge() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_reset_title)
            .setMessage(R.string.dialog_reset_message)
            .setPositiveButton(R.string.action_reset) { _, _ ->
                MemoryStore(this).reset()
                // 稼働中サービスの in-memory 記憶も破棄させる（書き戻し防止）
                sendBroadcast(Intent(OverlayService.ACTION_RESET_MEMORY).apply {
                    `package` = packageName
                })
                Toast.makeText(this, R.string.toast_data_purged, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}

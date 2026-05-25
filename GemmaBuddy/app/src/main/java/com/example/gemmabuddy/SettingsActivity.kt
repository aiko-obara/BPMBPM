package com.example.gemmabuddy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

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
    private lateinit var tvPersonality: TextView
    private lateinit var btnPersonality: Button
    private lateinit var tvNotificationStatus: TextView
    private lateinit var btnNotificationAccess: Button

    private var currentIntervalIdx = 3  // default 5m

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
        tvPersonality = findViewById(R.id.tv_personality_current)
        btnPersonality = findViewById(R.id.btn_personality_change)
        tvNotificationStatus = findViewById(R.id.tv_notification_status)
        btnNotificationAccess = findViewById(R.id.btn_notification_access)
    }

    private fun loadPrefs() {
        // Speed
        val speed = prefs.getString("typewriter_speed", "60")?.toLongOrNull() ?: 60L
        updateSpeedUI(speed)

        // Interval
        val intervalMs = prefs.getLong(OverlayService.PREF_INTERVAL_MS, OverlayService.DEFAULT_INTERVAL_MS)
        currentIntervalIdx = intervalSteps.indexOfFirst { it >= intervalMs }.coerceAtLeast(0)
        updateIntervalUI()

        // Personality
        updatePersonalityUI()

        // Notification access status
        updateNotificationStatusUI()
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
        btnPersonality.setOnClickListener { showPersonalityPicker() }
        btnNotificationAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
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

    private fun updatePersonalityUI() {
        val p = BuddyPersonality.load(prefs)
        tvPersonality.text = "${p.emoji} ${p.name}"
    }

    private fun showPersonalityPicker() {
        val items = BuddyPersonality.all.map { "${it.emoji} ${it.name}" }.toTypedArray()
        val current = BuddyPersonality.load(prefs)
        val currentIdx = BuddyPersonality.all.indexOfFirst { it.id == current.id }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("性格を選択")
            .setSingleChoiceItems(items, currentIdx) { dialog, which ->
                val selected = BuddyPersonality.all[which]
                prefs.edit().putString(BuddyPersonality.PREF_KEY, selected.id).apply()
                updatePersonalityUI()
                // サービスが起動中なら即時反映
                sendBroadcast(Intent(OverlayService.ACTION_RELOAD_PERSONALITY).apply {
                    `package` = packageName
                })
                Toast.makeText(this, "${selected.emoji} ${selected.name} に変更しました", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun updateNotificationStatusUI() {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        tvNotificationStatus.text = if (enabled) "通知アクセス: 有効 ✓" else "通知アクセス: 無効"
        tvNotificationStatus.setTextColor(
            if (enabled) getColor(R.color.nr_cyber_green) else getColor(R.color.nr_on_surface_variant)
        )
    }

    private fun confirmPurge() {
        AlertDialog.Builder(this)
            .setTitle("PURGE DATA")
            .setMessage("全ての記憶データを消去します。この操作は元に戻せません。")
            .setPositiveButton("PURGE") { _, _ ->
                MemoryStore(this).reset()
                Toast.makeText(this, "DATA PURGED", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}

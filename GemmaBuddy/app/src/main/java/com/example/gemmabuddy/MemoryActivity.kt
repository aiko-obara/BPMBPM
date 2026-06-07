package com.example.gemmabuddy

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemoryActivity : AppCompatActivity() {

    private lateinit var tvProfile: TextView
    private lateinit var tvImportant: TextView
    private lateinit var tvMonth: TextView
    private lateinit var tvEvents: TextView
    private lateinit var memoryStore: MemoryStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)
        setupBottomNav(NavTab.HISTORY)
        memoryStore = MemoryStore(this)
        tvProfile   = findViewById(R.id.tv_profile)
        tvImportant = findViewById(R.id.tv_important)
        tvMonth     = findViewById(R.id.tv_month)
        tvEvents    = findViewById(R.id.tv_events)

        findViewById<Button>(R.id.btn_reset).setOnClickListener { confirmReset() }

        render()
    }

    private fun render() {
        val mem = memoryStore.load()

        // プロファイル
        tvProfile.text = if (memoryStore.exists()) {
            mem.toSystemDigest()
        } else {
            getString(R.string.history_no_memory)
        }

        // 大切な思い出
        val importantList = memoryStore.loadImportantMemories()
        tvImportant.text = if (importantList.isEmpty()) {
            getString(R.string.history_no_important)
        } else {
            val dateFmt = SimpleDateFormat("yyyy/MM", Locale.JAPAN)
            importantList.joinToString("\n") { m ->
                "• ${dateFmt.format(Date(m.ts))}  ${m.text}"
            }
        }

        // 過去30日間の概要
        val monthGroups = memoryStore.loadMonthEvents()
        tvMonth.text = if (monthGroups.isEmpty()) {
            getString(R.string.history_no_records)
        } else {
            monthGroups.joinToString("\n") { g ->
                "${g.dayDate}  ${g.count}件"
            }
        }

        // 今日のイベント
        val df = SimpleDateFormat("HH:mm", Locale.JAPAN)
        tvEvents.text = if (mem.recentEvents.isEmpty()) {
            getString(R.string.history_no_logs)
        } else {
            mem.recentEvents.reversed().joinToString("\n") { ev ->
                val time = if (ev.ts > 0) df.format(Date(ev.ts)) else "--:--"
                "[$time] ${ev.comment}"
            }
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_reset_title)
            .setMessage(R.string.dialog_reset_message)
            .setPositiveButton(R.string.action_reset) { _, _ ->
                memoryStore.reset()
                // 稼働中サービスの in-memory 記憶も破棄させる（書き戻し防止）
                sendBroadcast(android.content.Intent(OverlayService.ACTION_RESET_MEMORY).apply {
                    `package` = packageName
                })
                Toast.makeText(this, R.string.toast_memory_reset, Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}

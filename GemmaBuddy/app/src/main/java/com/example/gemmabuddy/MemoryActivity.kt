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
            "まだ記憶がありません。キャラクターを起動すると観察が始まります。"
        }

        // 大切な思い出
        val importantList = memoryStore.loadImportantMemories()
        tvImportant.text = if (importantList.isEmpty()) {
            "（大切な思い出はまだありません）"
        } else {
            val dateFmt = SimpleDateFormat("yyyy/MM", Locale.JAPAN)
            importantList.joinToString("\n") { m ->
                "• ${dateFmt.format(Date(m.ts))}  ${m.text}"
            }
        }

        // 過去30日間の概要
        val monthGroups = memoryStore.loadMonthEvents()
        tvMonth.text = if (monthGroups.isEmpty()) {
            "（記録なし）"
        } else {
            monthGroups.joinToString("\n") { g ->
                "${g.dayDate}  ${g.count}件"
            }
        }

        // 今日のイベント
        val df = SimpleDateFormat("HH:mm", Locale.JAPAN)
        tvEvents.text = if (mem.recentEvents.isEmpty()) {
            "（本日の観察データなし）"
        } else {
            mem.recentEvents.reversed().joinToString("\n") { ev ->
                val time = if (ev.ts > 0) df.format(Date(ev.ts)) else "--:--"
                "[$time] ${ev.comment}"
            }
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("記憶をリセット")
            .setMessage("キャラクターの記憶を全て消去します。元には戻せません。よろしいですか？")
            .setPositiveButton("リセット") { _, _ ->
                memoryStore.reset()
                Toast.makeText(this, "記憶をリセットしました", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}

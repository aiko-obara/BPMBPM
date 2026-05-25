package com.example.gemmabuddy

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * CharacterMemory の永続化（SQLite バックエンド）。
 *
 * テーブル構成:
 *   events             — 30日ローリングウィンドウのイベント
 *   profile_snapshots  — 蒸留済みプロファイル（id=1 の1行）
 *   important_memories — 大切な思い出（恒久保存）
 *
 * コンテキスト構築:
 *   buildContextDigest() が プロファイル + 大切な思い出 + 30日概要 + 今日のイベント
 *   を組み合わせて Gemma へのシステムプロンプトを生成する。
 */
class MemoryStore(private val context: Context) {

    private val db  = AppDatabase.getInstance(context)
    private val dao = db.memoryDao()

    init {
        migrateLegacyMarkdownIfNeeded()
        migrateLegacyJsonIfNeeded()
    }

    // ─────────────────────────────────────────────
    // 基本 API（変更なし）
    // ─────────────────────────────────────────────

    fun load(): CharacterMemory {
        val snapshot = dao.getSnapshot()
        val mem = if (snapshot != null) {
            try {
                val wrapper = JSONObject().apply {
                    put("createdAt", snapshot.createdAt)
                    put("lastConsolidatedAt", snapshot.lastConsolidatedAt)
                    put("profile", JSONObject(snapshot.profileJson))
                    put("recent_events", JSONArray())
                }
                CharacterMemory.fromJson(wrapper)
            } catch (e: Exception) {
                Log.e(TAG, "プロファイル読み込み失敗、新規作成", e)
                CharacterMemory()
            }
        } else {
            CharacterMemory()
        }

        dao.getTodayEvents(todayDate()).forEach { row ->
            mem.recentEvents.addLast(CharacterMemory.Event(row.ts, row.comment))
        }
        return mem
    }

    fun save(memory: CharacterMemory) {
        // プロファイル保存
        val profileJson = memory.toJson().optJSONObject("profile")?.toString() ?: "{}"
        dao.upsertSnapshot(profileJson, memory.createdAt, memory.lastConsolidatedAt)

        // 全イベントを挿入（UNIQUE(ts,comment) で重複は自動スキップ）
        memory.recentEvents.forEach { ev ->
            dao.insertEventIgnoreDuplicate(ev.ts, ev.comment, tsToDate(ev.ts))
        }

        // 30日超のイベントを削除
        dao.pruneOldEvents(dateNDaysAgo(30))

        // memorableMoments → important_memories に sync
        syncImportantMemories(memory)

        Log.d(TAG, "記憶保存完了 events=${memory.recentEvents.size}")
    }

    fun reset() {
        dao.clearAll()
    }

    fun exists(): Boolean = dao.getSnapshot() != null

    // ─────────────────────────────────────────────
    // コンテキスト構築（Gemma 向け）
    // ─────────────────────────────────────────────

    /**
     * Gemma の system prompt に埋め込む記憶コンテキストを構築する。
     * ① プロファイルダイジェスト
     * ② 大切な思い出（最大10件）
     * ③ 過去30日間の概要（直近7日を詳細表示）
     * ④ 今日の観察（最新10件）
     */
    fun buildContextDigest(): String {
        val snapshot = dao.getSnapshot()
        val today = todayDate()

        // ① プロファイル
        val profileDigest = if (snapshot != null) {
            try {
                val wrapper = JSONObject().apply {
                    put("createdAt", snapshot.createdAt)
                    put("lastConsolidatedAt", snapshot.lastConsolidatedAt)
                    put("profile", JSONObject(snapshot.profileJson))
                    put("recent_events", JSONArray())
                }
                CharacterMemory.fromJson(wrapper).toSystemDigest()
            } catch (e: Exception) {
                "あなたはまだユーザーを観察し始めたばかりです。"
            }
        } else {
            "あなたはまだユーザーを観察し始めたばかりです。"
        }

        // ② 大切な思い出
        val importantMemories = dao.getImportantMemories().take(10)

        // ③ 過去30日間の概要
        val dayGroups = dao.getRecentDayGroups(dateNDaysAgo(30))

        // ④ 今日のイベント（最新10件、新しい順）
        val todayEvents = dao.getTodayEvents(today).takeLast(10)

        return buildString {
            append(profileDigest)

            if (importantMemories.isNotEmpty()) {
                append("\n\n【大切な思い出】\n")
                importantMemories.forEach { mem ->
                    val dateStr = SimpleDateFormat("yyyy/MM", Locale.JAPAN).format(Date(mem.ts))
                    append("- $dateStr: ${mem.text}\n")
                }
            }

            if (dayGroups.isNotEmpty()) {
                append("\n\n【過去30日間の活動概要】\n")
                dayGroups.take(7).forEach { g ->
                    append("- ${g.dayDate}: ${g.count}件の観察\n")
                }
                if (dayGroups.size > 7) {
                    append("  （他 ${dayGroups.size - 7} 日間の記録あり）\n")
                }
            }

            if (todayEvents.isNotEmpty()) {
                append("\n\n【今日の観察】\n")
                val timeFmt = SimpleDateFormat("HH:mm", Locale.JAPAN)
                todayEvents.forEach { ev ->
                    val time = if (ev.ts > 0L) timeFmt.format(Date(ev.ts)) else "--:--"
                    append("- $time ${ev.comment}\n")
                }
            }
        }.trimEnd()
    }

    // ─────────────────────────────────────────────
    // MemoryActivity 向けデータ取得
    // ─────────────────────────────────────────────

    fun loadImportantMemories(): List<ImportantMemoryRow> = dao.getImportantMemories()

    fun loadMonthEvents(): List<DayGroupRow> = dao.getRecentDayGroups(dateNDaysAgo(30))

    // ─────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────

    private fun syncImportantMemories(memory: CharacterMemory) {
        val now = System.currentTimeMillis()
        memory.profile.shared.memorableMoments.forEach { text ->
            if (text.isNotBlank()) dao.upsertImportantMemory(now, text)
        }
    }

    private fun todayDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun tsToDate(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))

    private fun dateNDaysAgo(n: Int): String {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -n) }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    // ─────────────────────────────────────────────
    // マイグレーション（旧ファイル → SQLite）
    // ─────────────────────────────────────────────

    // 旧 Markdown ファイル（memories/YYYY-MM-DD.md）が存在すれば DB に取り込んで削除
    private fun migrateLegacyMarkdownIfNeeded() {
        val dir = File(context.filesDir, "memories")
        if (!dir.exists()) return
        val mdFiles = dir.listFiles { f -> f.extension == "md" } ?: return
        if (mdFiles.isEmpty()) return

        var migratedCount = 0
        mdFiles.forEach { file ->
            try {
                val text = file.readText()
                val jsonStr = extractMarkdownJson(text) ?: return@forEach

                if (file.nameWithoutExtension == "profile") {
                    // profile.md → profile_snapshots
                    val obj = JSONObject(jsonStr)
                    val profileJson = obj.optJSONObject("profile")?.toString() ?: "{}"
                    dao.upsertSnapshot(
                        profileJson,
                        obj.optLong("createdAt", System.currentTimeMillis()),
                        obj.optLong("lastConsolidatedAt", 0L)
                    )
                } else {
                    // YYYY-MM-DD.md → events
                    val dayDate = file.nameWithoutExtension
                    if (dayDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                        val arr = JSONArray(jsonStr)
                        for (i in 0 until arr.length()) {
                            val ev = arr.optJSONObject(i) ?: continue
                            dao.insertEventIgnoreDuplicate(
                                ev.optLong("ts", 0L),
                                ev.optString("comment", ""),
                                dayDate
                            )
                        }
                    }
                }
                file.delete()
                migratedCount++
            } catch (e: Exception) {
                Log.e(TAG, "Markdown 移行失敗: ${file.name}", e)
            }
        }
        if (migratedCount > 0) {
            Log.i(TAG, "Markdown → SQLite 移行完了 ($migratedCount ファイル)")
            dir.delete() // 空になったら削除
        }
    }

    /** 旧 character_memory.json が存在すれば DB に取り込んで削除 */
    private fun migrateLegacyJsonIfNeeded() {
        val file = File(context.filesDir, LEGACY_JSON)
        if (!file.exists()) return
        try {
            val mem = CharacterMemory.fromJson(JSONObject(file.readText()))
            save(mem)
            file.delete()
            File(context.filesDir, "$LEGACY_JSON.tmp").delete()
            Log.i(TAG, "レガシー JSON → SQLite 移行完了")
        } catch (e: Exception) {
            Log.e(TAG, "レガシー JSON 移行失敗（スキップ）", e)
        }
    }

    private fun extractMarkdownJson(text: String): String? {
        val start = text.indexOf("<!-- json")
        val end = text.indexOf("-->", if (start >= 0) start else 0)
        if (start < 0 || end < 0) return null
        return text.substring(start + 9, end).trim()
    }

    companion object {
        private const val TAG = "MemoryStore"
        private const val LEGACY_JSON = "character_memory.json"
        const val FILE_NAME = "gemmabuddy.db"
    }
}

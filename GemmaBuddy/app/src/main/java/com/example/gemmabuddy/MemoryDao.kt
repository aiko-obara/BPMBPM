package com.example.gemmabuddy

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

data class MemorySnapshotRow(
    val id: Int,
    val profileJson: String,
    val createdAt: Long,
    val lastConsolidatedAt: Long
)

data class EventRow(
    val id: Long,
    val ts: Long,
    val comment: String,
    val dayDate: String,
    val isImportant: Boolean
)

data class DayGroupRow(
    val dayDate: String,
    val count: Int
)

data class ImportantMemoryRow(
    val id: Long,
    val ts: Long,
    val text: String
)

class MemoryDao(private val db: AppDatabase) {

    // ─────────────────────────────────────────────
    // プロファイル
    // ─────────────────────────────────────────────

    fun getSnapshot(): MemorySnapshotRow? {
        val c = db.readableDatabase.rawQuery(
            "SELECT id, profile_json, created_at, last_consolidated_at FROM profile_snapshots WHERE id = 1",
            null
        )
        return c.use {
            if (!it.moveToFirst()) null
            else MemorySnapshotRow(it.getInt(0), it.getString(1), it.getLong(2), it.getLong(3))
        }
    }

    fun upsertSnapshot(profileJson: String, createdAt: Long, lastConsolidatedAt: Long) {
        val cv = ContentValues().apply {
            put("id", 1)
            put("profile_json", profileJson)
            put("created_at", createdAt)
            put("last_consolidated_at", lastConsolidatedAt)
        }
        db.writableDatabase.insertWithOnConflict(
            "profile_snapshots", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ─────────────────────────────────────────────
    // イベント
    // ─────────────────────────────────────────────

    /** UNIQUE(ts, comment) 制約で重複は無視して挿入 */
    fun insertEventIgnoreDuplicate(ts: Long, comment: String, dayDate: String) {
        db.writableDatabase.execSQL(
            "INSERT OR IGNORE INTO events(ts, comment, day_date) VALUES(?, ?, ?)",
            arrayOf<Any>(ts, comment, dayDate)
        )
    }

    fun getTodayEvents(dayDate: String): List<EventRow> {
        val c = db.readableDatabase.rawQuery(
            "SELECT id, ts, comment, day_date, is_important FROM events WHERE day_date = ? ORDER BY ts ASC",
            arrayOf(dayDate)
        )
        return c.use { cur ->
            buildList {
                while (cur.moveToNext()) {
                    add(EventRow(cur.getLong(0), cur.getLong(1), cur.getString(2), cur.getString(3), cur.getInt(4) != 0))
                }
            }
        }
    }

    /** 指定日以降を day_date でグループ集計（新しい順） */
    fun getRecentDayGroups(sinceDate: String): List<DayGroupRow> {
        val c = db.readableDatabase.rawQuery(
            """SELECT day_date, COUNT(*) as cnt
               FROM events WHERE day_date >= ?
               GROUP BY day_date ORDER BY day_date DESC""",
            arrayOf(sinceDate)
        )
        return c.use { cur ->
            buildList {
                while (cur.moveToNext()) {
                    add(DayGroupRow(cur.getString(0), cur.getInt(1)))
                }
            }
        }
    }

    /** beforeDate より古いイベントを削除（30日ローリングウィンドウ保守） */
    fun pruneOldEvents(beforeDate: String) {
        db.writableDatabase.delete("events", "day_date < ?", arrayOf(beforeDate))
    }

    // ─────────────────────────────────────────────
    // 大切な思い出
    // ─────────────────────────────────────────────

    fun getImportantMemories(): List<ImportantMemoryRow> {
        val c = db.readableDatabase.rawQuery(
            "SELECT id, ts, text FROM important_memories ORDER BY ts DESC",
            null
        )
        return c.use { cur ->
            buildList {
                while (cur.moveToNext()) {
                    add(ImportantMemoryRow(cur.getLong(0), cur.getLong(1), cur.getString(2)))
                }
            }
        }
    }

    /** UNIQUE(text) 制約で既存エントリは ts のみ更新 */
    fun upsertImportantMemory(ts: Long, text: String) {
        db.writableDatabase.execSQL(
            "INSERT INTO important_memories(ts, text) VALUES(?, ?) ON CONFLICT(text) DO UPDATE SET ts=excluded.ts",
            arrayOf<Any>(ts, text)
        )
    }

    fun deleteImportantMemory(id: Long) {
        db.writableDatabase.delete("important_memories", "id = ?", arrayOf(id.toString()))
    }

    fun clearAll() {
        db.writableDatabase.apply {
            delete("events", null, null)
            delete("profile_snapshots", null, null)
            delete("important_memories", null, null)
        }
    }
}

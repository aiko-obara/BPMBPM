package com.example.gemmabuddy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        // イベント（30日ローリングウィンドウ）
        db.execSQL(
            """CREATE TABLE events (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                ts           INTEGER NOT NULL,
                comment      TEXT    NOT NULL,
                day_date     TEXT    NOT NULL,
                is_important INTEGER NOT NULL DEFAULT 0,
                UNIQUE(ts, comment)
            )"""
        )
        db.execSQL("CREATE INDEX idx_events_day ON events(day_date)")

        // プロファイルスナップショット（常に id=1 の1行）
        db.execSQL(
            """CREATE TABLE profile_snapshots (
                id                   INTEGER PRIMARY KEY,
                profile_json         TEXT    NOT NULL,
                created_at           INTEGER NOT NULL,
                last_consolidated_at INTEGER NOT NULL
            )"""
        )

        // 大切な思い出（プロファイルリセット後も生き残る恒久テーブル）
        db.execSQL(
            """CREATE TABLE important_memories (
                id   INTEGER PRIMARY KEY AUTOINCREMENT,
                ts   INTEGER NOT NULL,
                text TEXT    NOT NULL UNIQUE
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS events")
        db.execSQL("DROP TABLE IF EXISTS profile_snapshots")
        db.execSQL("DROP TABLE IF EXISTS important_memories")
        onCreate(db)
    }

    fun memoryDao(): MemoryDao = MemoryDao(this)

    companion object {
        private const val DB_NAME = "gemmabuddy.db"
        private const val DB_VERSION = 1

        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: AppDatabase(context).also { instance = it }
            }
    }
}

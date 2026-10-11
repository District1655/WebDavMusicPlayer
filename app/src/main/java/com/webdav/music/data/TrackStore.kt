package com.webdav.music.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite 本地缓存（替代 Room，绕开 JDK 24 注解处理兼容问题）
 * API 与 Room 版本保持一致：getAll / insertAll / deleteAll / count
 */
class TrackStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {

    companion object {
        private const val DB_NAME = "webdav_music.db"
        private const val DB_VERSION = 2
        private const val TABLE = "tracks"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                path TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                size INTEGER NOT NULL,
                isDirectory INTEGER NOT NULL DEFAULT 0,
                scannedAt INTEGER NOT NULL,
                lrcPath TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_name ON $TABLE(name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            // v1 → v2：新增歌词路径列，保留已缓存曲库
            try {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN lrcPath TEXT")
            } catch (_: Exception) {
                db.execSQL("DROP TABLE IF EXISTS $TABLE")
                onCreate(db)
            }
        }
    }

    /** 查全部曲目，按名字排序 */
    fun getAll(): List<Track> {
        val result = mutableListOf<Track>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE ORDER BY name COLLATE NOCASE ASC", null
        ).use { c ->
            while (c.moveToNext()) {
                val lrcIdx = c.getColumnIndexOrThrow("lrcPath")
                result.add(Track(
                    path = c.getString(c.getColumnIndexOrThrow("path")),
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    size = c.getLong(c.getColumnIndexOrThrow("size")),
                    isDirectory = c.getInt(c.getColumnIndexOrThrow("isDirectory")) == 1,
                    scannedAt = c.getLong(c.getColumnIndexOrThrow("scannedAt")),
                    lrcPath = if (!c.isNull(lrcIdx)) c.getString(lrcIdx) else null
                ))
            }
        }
        return result
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 全量刷新：清空后批量插入 */
    fun replaceAll(tracks: List<Track>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE, null, null)
            tracks.forEach { t ->
                val cv = ContentValues().apply {
                    put("path", t.path)
                    put("name", t.name)
                    put("size", t.size)
                    put("isDirectory", if (t.isDirectory) 1 else 0)
                    put("scannedAt", t.scannedAt)
                    if (t.lrcPath != null) put("lrcPath", t.lrcPath)
                }
                db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

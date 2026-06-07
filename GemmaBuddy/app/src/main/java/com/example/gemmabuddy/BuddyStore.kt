package com.example.gemmabuddy

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class BuddyEntry(
    val id: String,
    val name: String,
    val templateType: String,
    val fileName: String,
    val createdAt: Long,
    val isActive: Boolean = false,
    val defaultPersonalityId: String? = null
)

/**
 * 複数キャラクターの保存・切替・削除を管理する。
 *
 * ディレクトリ構成:
 *   filesDir/buddies/index.json  … メタデータ
 *   filesDir/buddies/<id>.png    … 各キャラ画像 (256×256)
 *
 * 切替時は選択キャラの PNG を filesDir/custom_character.png にコピーする。
 * OverlayService は ACTION_RELOAD_CHARACTER を受信して再読み込みするだけなので変更不要。
 */
class BuddyStore(private val context: Context) {

    private val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
    private val indexFile = File(dir, INDEX_FILE)
    private val tmpFile = File(dir, "$INDEX_FILE.tmp")
    private val lock = Any()

    // ─────────────────────────────────────────────────────────────────────
    // 公開 API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 新しいキャラクターを保存してアクティブに設定する。
     * normal2Bitmap / speakingBitmap を渡すとマルチフレームとして保存する。
     */
    fun saveNew(
        name: String,
        bitmap: Bitmap,
        templateType: String,
        normal2Bitmap: Bitmap? = null,
        speakingBitmap: Bitmap? = null
    ): BuddyEntry {
        val id = System.currentTimeMillis().toString()
        val fileName = "$id.png"
        val pngFile = File(dir, fileName)

        FileOutputStream(pngFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        normal2Bitmap?.let { bmp ->
            FileOutputStream(File(dir, "${id}_2.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        speakingBitmap?.let { bmp ->
            FileOutputStream(File(dir, "${id}_speaking.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        val entry = BuddyEntry(
            id = id,
            name = name,
            templateType = templateType,
            fileName = fileName,
            createdAt = System.currentTimeMillis(),
            isActive = true
        )

        synchronized(lock) {
            val current = loadIndex().map { it.copy(isActive = false) }
            saveIndex(current + entry)
        }

        // active files にコピー
        pngFile.copyTo(File(context.filesDir, OverlayService.CUSTOM_CHAR_FILE), overwrite = true)
        copyExtraFramesToActive(id, normal2Bitmap != null, speakingBitmap != null)
        return entry
    }

    /** 全キャラクターを新しい順に返す。 */
    fun loadAll(): List<BuddyEntry> = synchronized(lock) {
        loadIndex().sortedByDescending { it.createdAt }
    }

    /** 指定 id のキャラをアクティブ化し、custom_character*.png を更新する。 */
    fun setActive(id: String) {
        val active: BuddyEntry
        synchronized(lock) {
            val entries = loadIndex().map { it.copy(isActive = it.id == id) }
            saveIndex(entries)
            active = entries.find { it.isActive } ?: return
            val src = File(dir, active.fileName)
            if (src.exists()) {
                src.copyTo(File(context.filesDir, OverlayService.CUSTOM_CHAR_FILE), overwrite = true)
            }
            val hasNormal2 = File(dir, "${active.id}_2.png").exists()
            val hasSpeaking = File(dir, "${active.id}_speaking.png").exists()
            copyExtraFramesToActive(active.id, hasNormal2, hasSpeaking)
        }
        // バディに紐付いた性格があれば追従させる
        applyPersonalityIfSet(active)
    }

    /** 指定 id のキャラに性格を紐付ける。アクティブなら即座に反映する。 */
    fun setPersonality(id: String, personalityId: String) {
        val updated: BuddyEntry
        synchronized(lock) {
            val entries = loadIndex().map {
                if (it.id == id) it.copy(defaultPersonalityId = personalityId) else it
            }
            saveIndex(entries)
            updated = entries.find { it.id == id } ?: return
        }
        if (updated.isActive) applyPersonalityIfSet(updated)
    }

    /** entry.defaultPersonalityId を端末の性格設定へ反映し、稼働中サービスに通知する。 */
    private fun applyPersonalityIfSet(entry: BuddyEntry) {
        val personalityId = entry.defaultPersonalityId ?: return
        val prefs = context.getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(BuddyPersonality.PREF_KEY, personalityId).apply()
        context.sendBroadcast(Intent(OverlayService.ACTION_RELOAD_PERSONALITY).apply {
            `package` = context.packageName
        })
    }

    /** 指定 id のキャラを削除する。アクティブだった場合は次の候補をアクティブ化。 */
    fun delete(id: String) {
        synchronized(lock) {
            val all = loadIndex()
            val target = all.find { it.id == id } ?: return
            val remaining = all.filter { it.id != id }

            // ファイル削除
            File(dir, target.fileName).delete()

            if (target.isActive && remaining.isNotEmpty()) {
                // 次のキャラをアクティブ化
                val next = remaining.maxByOrNull { it.createdAt }!!
                saveIndex(remaining.map { it.copy(isActive = it.id == next.id) })
                val src = File(dir, next.fileName)
                if (src.exists()) {
                    src.copyTo(File(context.filesDir, OverlayService.CUSTOM_CHAR_FILE), overwrite = true)
                }
            } else {
                saveIndex(remaining)
                if (target.isActive) {
                    // 全削除した場合は custom_character.png を消す
                    File(context.filesDir, OverlayService.CUSTOM_CHAR_FILE).delete()
                }
            }
        }
    }

    /** BuddyEntry の画像ファイルを返す。 */
    fun getFile(entry: BuddyEntry): File = File(dir, entry.fileName)

    // ─────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun copyExtraFramesToActive(id: String, hasNormal2: Boolean, hasSpeaking: Boolean) {
        val normal2Dst = File(context.filesDir, CUSTOM_CHAR_NORMAL2_FILE)
        val speakingDst = File(context.filesDir, CUSTOM_CHAR_SPEAKING_FILE)

        if (hasNormal2) {
            File(dir, "${id}_2.png").copyTo(normal2Dst, overwrite = true)
        } else {
            normal2Dst.delete()
        }
        if (hasSpeaking) {
            File(dir, "${id}_speaking.png").copyTo(speakingDst, overwrite = true)
        } else {
            speakingDst.delete()
        }
    }

    /** アクティブなキャラクターを返す。 */
    fun getActive(): BuddyEntry? = loadAll().firstOrNull { it.isActive }

    // ─────────────────────────────────────────────────────────────────────
    // Private
    // ─────────────────────────────────────────────────────────────────────

    private fun loadIndex(): List<BuddyEntry> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val obj = JSONObject(indexFile.readText())
            val activeId = obj.optString("activeId", "")
            val arr = obj.optJSONArray("buddies") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val b = arr.optJSONObject(i) ?: return@mapNotNull null
                BuddyEntry(
                    id = b.getString("id"),
                    name = b.getString("name"),
                    templateType = b.optString("templateType", "HUMAN"),
                    fileName = b.getString("fileName"),
                    createdAt = b.getLong("createdAt"),
                    isActive = b.getString("id") == activeId,
                    defaultPersonalityId = b.optString("defaultPersonalityId", "").ifBlank { null }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "index 読み込み失敗", e)
            emptyList()
        }
    }

    private fun saveIndex(entries: List<BuddyEntry>) {
        try {
            val activeId = entries.find { it.isActive }?.id ?: ""
            val obj = JSONObject().apply {
                put("activeId", activeId)
                put("buddies", JSONArray().apply {
                    entries.forEach { e ->
                        put(JSONObject().apply {
                            put("id", e.id)
                            put("name", e.name)
                            put("templateType", e.templateType)
                            put("fileName", e.fileName)
                            put("createdAt", e.createdAt)
                            e.defaultPersonalityId?.let { put("defaultPersonalityId", it) }
                        })
                    }
                })
            }
            tmpFile.writeText(obj.toString())
            if (indexFile.exists()) indexFile.delete()
            tmpFile.renameTo(indexFile)
        } catch (e: Exception) {
            Log.e(TAG, "index 保存失敗", e)
        }
    }

    companion object {
        private const val TAG = "BuddyStore"
        private const val DIR_NAME = "buddies"
        private const val INDEX_FILE = "index.json"
        const val CUSTOM_CHAR_NORMAL2_FILE = "custom_character_2.png"
        const val CUSTOM_CHAR_SPEAKING_FILE = "custom_character_speaking.png"
    }
}

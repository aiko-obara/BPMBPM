package com.example.gemmabuddy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

data class CharacterFrames(
    val normal1: Bitmap,
    val normal2: Bitmap?,
    val speaking: Bitmap?
)

class BuddyImageProvider(private val context: Context) {

    private var shuffled: List<String> = emptyList()
    private var cursor: Int = -1

    /** characters/ 直下のフォルダ一覧をソートして返す */
    fun listFolders(): List<String> = try {
        context.assets.list(DIR)
            ?.filter { name ->
                // ファイルでなくフォルダのみ（.DS_Store 等を除外）
                context.assets.list("$DIR/$name")?.isNotEmpty() == true
            }
            ?.sorted() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun hasAny(): Boolean = listFolders().isNotEmpty()

    fun hasMultiple(): Boolean = listFolders().size > 1

    /** フォルダからフレームをすべてロードする */
    fun loadFrames(folder: String): CharacterFrames? {
        val normal1 = loadBitmap("$DIR/$folder/normal1.png") ?: return null
        val normal2 = loadBitmap("$DIR/$folder/normal2.png")
        val speaking = loadBitmap("$DIR/$folder/speaking.png")
        return CharacterFrames(normal1, normal2, speaking)
    }

    /** サムネイル用（normal1 をダウンサンプリング） */
    fun loadThumbnail(folder: String, maxSizePx: Int = 256): Bitmap? =
        loadThumbnailFrom("$DIR/$folder/normal1.png", maxSizePx)

    /** フォルダ単位でランダムに次を返す */
    fun pickNext(): Pair<String, CharacterFrames>? {
        val all = listFolders()
        if (all.isEmpty()) return null
        if (shuffled.size != all.size) {
            shuffled = all.shuffled()
            cursor = 0
        } else {
            cursor = (cursor + 1) % shuffled.size
        }
        val folder = shuffled[cursor]
        val frames = loadFrames(folder) ?: return null
        return folder to frames
    }

    // ─────────────────────────────────────────────────────────────────────

    private fun loadBitmap(assetPath: String): Bitmap? = try {
        context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    private fun loadThumbnailFrom(assetPath: String, maxSizePx: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, opts) }

        var sample = 1
        while (opts.outWidth / sample > maxSizePx || opts.outHeight / sample > maxSizePx) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, decodeOpts) }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val DIR = "characters"

        /** `NNN_name` → `Name` 形式の表示名を生成 */
        fun displayName(folder: String): String =
            folder.replaceFirst(Regex("^\\d+_"), "")
                .replace('_', ' ')
                .split(' ')
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
}

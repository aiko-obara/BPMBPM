package com.example.gemmabuddy

import android.graphics.Bitmap

/**
 * Gemma 4 が出力した「32×32 のデジット格子」テキストを Bitmap に変換する。
 *
 * 想定入力（理想）:
 *   00000000111110000...   (32桁)
 *   00000001122211000...
 *   ... (32行)
 *
 * 実際は LLM が説明文を挟んだり桁数がズレたりするため robust にパースする：
 *   1. 改行で分割
 *   2. 各行から数字のみ抽出
 *   3. 数字 16 個未満の行は無視
 *   4. 上から 32 行採用、各行先頭 32 桁を採用、足りない桁は 0 で埋める
 *   5. 行が 32 行に満たない場合は下を 0 で埋める
 */
object PixelGridParser {

    const val GRID_SIZE = 32

    /** パレット（インデックス 0-9 → ARGB） */
    val PALETTE = intArrayOf(
        0x00000000,                  // 0 = transparent
        0xFF000000.toInt(),          // 1 = black
        0xFFFFFFFF.toInt(),          // 2 = white
        0xFFFFCC99.toInt(),          // 3 = skin
        0xFF663300.toInt(),          // 4 = dark brown
        0xFFFFE066.toInt(),          // 5 = yellow
        0xFFFF6B6B.toInt(),          // 6 = red
        0xFF4DABF7.toInt(),          // 7 = blue
        0xFF51CF66.toInt(),          // 8 = green
        0xFF868E96.toInt()           // 9 = gray
    )

    /**
     * @param text LLM 出力
     * @return 32×32 の ARGB Bitmap（透過対応）
     */
    fun parse(text: String): Bitmap {
        val grid = IntArray(GRID_SIZE * GRID_SIZE) { 0 }
        val candidateLines = text.split('\n')
            .map { line -> line.filter { it.isDigit() } }
            .filter { it.length >= GRID_SIZE / 2 }  // 半分以上の桁がある行のみ採用

        val gridLines = candidateLines.take(GRID_SIZE)
        for ((rowIdx, digits) in gridLines.withIndex()) {
            val take = minOf(digits.length, GRID_SIZE)
            for (colIdx in 0 until take) {
                val idx = digits[colIdx].digitToInt()
                grid[rowIdx * GRID_SIZE + colIdx] = idx.coerceIn(0, PALETTE.size - 1)
            }
        }

        val argbPixels = IntArray(GRID_SIZE * GRID_SIZE) { i -> PALETTE[grid[i]] }
        val bitmap = Bitmap.createBitmap(GRID_SIZE, GRID_SIZE, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(argbPixels, 0, GRID_SIZE, 0, 0, GRID_SIZE, GRID_SIZE)
        return bitmap
    }

    /** 32x32 → 表示サイズへ nearest-neighbor 拡大（ピクセル感を残す） */
    fun upscale(src: Bitmap, targetSize: Int = 256): Bitmap {
        return Bitmap.createScaledBitmap(src, targetSize, targetSize, false)
    }

    /** パース結果の妥当性チェック（全部透明なら失敗扱い） */
    fun isLikelyValid(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val nonTransparentCount = pixels.count { (it ushr 24) and 0xFF > 0 }
        return nonTransparentCount > pixels.size / 20  // 5% 以上が non-transparent なら OK
    }
}

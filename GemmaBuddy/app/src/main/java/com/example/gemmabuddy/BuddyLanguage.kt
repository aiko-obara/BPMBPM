package com.example.gemmabuddy

import android.content.Context
import android.content.SharedPreferences

/**
 * Buddy が「話す」言語の設定。アプリの UI 言語（AppCompatDelegate の per-app locale）とは
 * 独立しており、この pref のみで決まる。"ja" / "en"。
 *
 * Buddy 発話の固定文言（別れの挨拶・歩数マイルストーン等）はローカライズ resource ではなく
 * ここを起点にコード内で ja/en を選ぶ（UI ロケールに引きずられないようにするため）。
 */
object BuddyLanguage {
    const val PREF_KEY = "buddy_language"

    fun isEnglish(prefs: SharedPreferences): Boolean =
        (prefs.getString(PREF_KEY, "ja") ?: "ja") == "en"

    fun isEnglish(context: Context): Boolean =
        isEnglish(context.getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE))
}

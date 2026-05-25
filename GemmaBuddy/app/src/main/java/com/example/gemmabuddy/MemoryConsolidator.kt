package com.example.gemmabuddy

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Recent Events と現プロファイルを Gemma 4 に渡し、更新済みプロファイルを抽出する。
 * GemmaManager に「整理用」のテキスト一発推論を依頼するシンプルな実装。
 */
class MemoryConsolidator(private val gemmaManager: GemmaManager) {

    /**
     * @return 更新された CharacterMemory（同じインスタンスを返す。失敗時は null）
     */
    suspend fun consolidate(memory: CharacterMemory): CharacterMemory? = withContext(Dispatchers.IO) {
        if (memory.recentEvents.isEmpty()) return@withContext null
        val prompt = buildPrompt(memory)
        Log.i(TAG, "Consolidation 開始 (events=${memory.recentEvents.size})")

        val raw = gemmaManager.consolidationRequest(prompt)
        if (raw.isBlank()) {
            Log.w(TAG, "Gemma の出力が空")
            return@withContext null
        }
        Log.i(TAG, "Gemma raw output:\n$raw")

        val updated = parseAndMerge(raw, memory)
        if (updated == null) {
            Log.w(TAG, "JSON パース失敗")
            return@withContext null
        }
        updated.markConsolidated()
        return@withContext updated
    }

    private fun buildPrompt(memory: CharacterMemory): String = buildString {
        append("You are updating a digital character's long-term memory profile based on recent observations.\n\n")
        append("CURRENT PROFILE (JSON):\n")
        append(memory.toJson().getJSONObject("profile").toString())
        append("\n\nRECENT OBSERVATIONS (chronological character comments while watching the user):\n")
        memory.recentEvents.forEach { ev ->
            append("- \"${ev.comment}\"\n")
        }
        append("\nFrom these observations, update the profile by:\n")
        append("- ADD new interests, activities, schedule patterns, preferences you can INFER about the user.\n")
        append("- REFINE character personality_traits, favorite_topics, tone based on the comments themselves.\n")
        append("- Note recurring_themes and memorable_moments.\n")
        append("- Keep each array at most ${CharacterMemory.MAX_ARRAY_ITEMS} items, each item under 30 chars.\n")
        append("- Output Japanese strings (except keys).\n")
        append("\nOutput ONLY the updated profile JSON, no markdown, no explanation. Same structure as input.\n")
    }

    /**
     * Gemma の出力テキストから JSON を抽出してマージ。
     * 出力が JSON でない、または構造が違う場合は null。
     */
    private fun parseAndMerge(raw: String, memory: CharacterMemory): CharacterMemory? {
        val jsonText = extractJsonBlock(raw) ?: return null
        return try {
            val obj = JSONObject(jsonText)
            obj.optJSONObject("user")?.let { u ->
                replaceList(memory.profile.user.interests, u.optJSONArray("interests"))
                replaceList(memory.profile.user.activities, u.optJSONArray("activities"))
                replaceList(memory.profile.user.schedulePatterns, u.optJSONArray("schedule_patterns"))
                replaceList(memory.profile.user.preferences, u.optJSONArray("preferences"))
            }
            obj.optJSONObject("character")?.let { c ->
                replaceList(memory.profile.character.personalityTraits, c.optJSONArray("personality_traits"))
                replaceList(memory.profile.character.favoriteTopics, c.optJSONArray("favorite_topics"))
                c.optString("tone").takeIf { it.isNotBlank() }?.let { memory.profile.character.tone = it.take(30) }
            }
            obj.optJSONObject("shared")?.let { s ->
                replaceList(memory.profile.shared.recurringThemes, s.optJSONArray("recurring_themes"))
                replaceList(memory.profile.shared.memorableMoments, s.optJSONArray("memorable_moments"))
            }
            memory
        } catch (e: Exception) {
            Log.e(TAG, "JSON 解析エラー", e)
            null
        }
    }

    /** 出力から最初の `{ ... }` を抽出（前後の説明文を許容） */
    private fun extractJsonBlock(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    private fun replaceList(target: MutableList<String>, arr: org.json.JSONArray?) {
        if (arr == null) return
        target.clear()
        for (i in 0 until minOf(arr.length(), CharacterMemory.MAX_ARRAY_ITEMS)) {
            val s = arr.optString(i, "").trim().take(30)
            if (s.isNotEmpty()) target.add(s)
        }
    }

    companion object {
        private const val TAG = "MemoryConsolidator"
    }
}

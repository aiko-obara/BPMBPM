package com.example.gemmabuddy

import org.json.JSONArray
import org.json.JSONObject

/**
 * キャラクターの長期記憶。1 アプリにつき 1 インスタンス。
 *
 * 三層構造:
 *   - profile: 永続化された人格 + ユーザー観察結果（consolidation で更新）
 *   - recentEvents: 直近の観察リングバッファ（最新 30 件、consolidation の入力源）
 *   - working memory は litertlm の Conversation オブジェクトが保持（本クラス外）
 */
data class CharacterMemory(
    val createdAt: Long = System.currentTimeMillis(),
    var lastConsolidatedAt: Long = 0L,
    val profile: Profile = Profile(),
    val recentEvents: ArrayDeque<Event> = ArrayDeque()
) {

    data class Profile(
        val user: UserProfile = UserProfile(),
        val character: CharacterProfile = CharacterProfile(),
        val shared: SharedProfile = SharedProfile()
    )

    data class UserProfile(
        val interests: MutableList<String> = mutableListOf(),
        val activities: MutableList<String> = mutableListOf(),
        val schedulePatterns: MutableList<String> = mutableListOf(),
        val preferences: MutableList<String> = mutableListOf()
    )

    data class CharacterProfile(
        val personalityTraits: MutableList<String> = mutableListOf(),
        val favoriteTopics: MutableList<String> = mutableListOf(),
        var tone: String = "親しみやすい"
    )

    data class SharedProfile(
        val recurringThemes: MutableList<String> = mutableListOf(),
        val memorableMoments: MutableList<String> = mutableListOf()
    )

    data class Event(
        val ts: Long,
        val comment: String
    )

    /** Event を追加。容量超過したら古いものから削除。 */
    @Synchronized
    fun addEvent(comment: String, maxBuffer: Int = RECENT_BUFFER_SIZE) {
        recentEvents.addLast(Event(System.currentTimeMillis(), comment.take(120)))
        while (recentEvents.size > maxBuffer) recentEvents.removeFirst()
    }

    /** Consolidation 完了時に呼ぶ。直近 5 件は次回の入力として残し、それ以前は削除。 */
    @Synchronized
    fun markConsolidated(keepRecent: Int = 5) {
        lastConsolidatedAt = System.currentTimeMillis()
        while (recentEvents.size > keepRecent) recentEvents.removeFirst()
    }

    /** Consolidation を実行すべきか */
    fun shouldConsolidate(): Boolean {
        if (recentEvents.size >= CONSOLIDATE_EVENT_THRESHOLD) return true
        val now = System.currentTimeMillis()
        return recentEvents.isNotEmpty() &&
            lastConsolidatedAt > 0L &&
            (now - lastConsolidatedAt) >= CONSOLIDATE_INTERVAL_MS
    }

    /** system instruction に組み込む人格ダイジェスト */
    fun toSystemDigest(): String {
        val u = profile.user
        val c = profile.character
        val s = profile.shared
        val hasAny = listOf(
            u.interests, u.activities, u.schedulePatterns, u.preferences,
            c.personalityTraits, c.favoriteTopics,
            s.recurringThemes, s.memorableMoments
        ).any { it.isNotEmpty() }

        if (!hasAny) {
            return "あなたはまだユーザーを観察し始めたばかりです。"
        }

        return buildString {
            append("【あなたが今まで観察してきたユーザー】\n")
            if (u.interests.isNotEmpty()) append("興味: ${u.interests.joinToString(", ")}\n")
            if (u.activities.isNotEmpty()) append("活動傾向: ${u.activities.joinToString(", ")}\n")
            if (u.schedulePatterns.isNotEmpty()) append("習慣: ${u.schedulePatterns.joinToString(", ")}\n")
            if (u.preferences.isNotEmpty()) append("好み: ${u.preferences.joinToString(", ")}\n")
            append("\n【あなたの人格】\n")
            if (c.personalityTraits.isNotEmpty()) append("性格: ${c.personalityTraits.joinToString(", ")}\n")
            append("口調: ${c.tone}\n")
            if (c.favoriteTopics.isNotEmpty()) append("好きな話題: ${c.favoriteTopics.joinToString(", ")}\n")
            if (s.recurringThemes.isNotEmpty() || s.memorableMoments.isNotEmpty()) {
                append("\n【共有の思い出】\n")
                if (s.recurringThemes.isNotEmpty()) append("最近のテーマ: ${s.recurringThemes.joinToString(", ")}\n")
                if (s.memorableMoments.isNotEmpty()) append("印象的だったこと: ${s.memorableMoments.joinToString(", ")}\n")
            }
        }.trimEnd()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("createdAt", createdAt)
        put("lastConsolidatedAt", lastConsolidatedAt)
        put("profile", JSONObject().apply {
            put("user", JSONObject().apply {
                put("interests", JSONArray(profile.user.interests))
                put("activities", JSONArray(profile.user.activities))
                put("schedule_patterns", JSONArray(profile.user.schedulePatterns))
                put("preferences", JSONArray(profile.user.preferences))
            })
            put("character", JSONObject().apply {
                put("personality_traits", JSONArray(profile.character.personalityTraits))
                put("favorite_topics", JSONArray(profile.character.favoriteTopics))
                put("tone", profile.character.tone)
            })
            put("shared", JSONObject().apply {
                put("recurring_themes", JSONArray(profile.shared.recurringThemes))
                put("memorable_moments", JSONArray(profile.shared.memorableMoments))
            })
        })
        put("recent_events", JSONArray().apply {
            recentEvents.forEach { ev ->
                put(JSONObject().apply {
                    put("ts", ev.ts)
                    put("comment", ev.comment)
                })
            }
        })
    }

    companion object {
        const val RECENT_BUFFER_SIZE = 30
        const val CONSOLIDATE_EVENT_THRESHOLD = 10
        const val CONSOLIDATE_INTERVAL_MS = 60L * 60 * 1000  // 1 時間
        const val MAX_ARRAY_ITEMS = 8

        fun fromJson(json: JSONObject): CharacterMemory {
            val mem = CharacterMemory(
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                lastConsolidatedAt = json.optLong("lastConsolidatedAt", 0L)
            )
            json.optJSONObject("profile")?.let { p ->
                p.optJSONObject("user")?.let { u ->
                    mem.profile.user.interests.addAll(toStringList(u.optJSONArray("interests")))
                    mem.profile.user.activities.addAll(toStringList(u.optJSONArray("activities")))
                    mem.profile.user.schedulePatterns.addAll(toStringList(u.optJSONArray("schedule_patterns")))
                    mem.profile.user.preferences.addAll(toStringList(u.optJSONArray("preferences")))
                }
                p.optJSONObject("character")?.let { c ->
                    mem.profile.character.personalityTraits.addAll(toStringList(c.optJSONArray("personality_traits")))
                    mem.profile.character.favoriteTopics.addAll(toStringList(c.optJSONArray("favorite_topics")))
                    mem.profile.character.tone = c.optString("tone", "親しみやすい")
                }
                p.optJSONObject("shared")?.let { s ->
                    mem.profile.shared.recurringThemes.addAll(toStringList(s.optJSONArray("recurring_themes")))
                    mem.profile.shared.memorableMoments.addAll(toStringList(s.optJSONArray("memorable_moments")))
                }
            }
            json.optJSONArray("recent_events")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val ev = arr.optJSONObject(i) ?: continue
                    mem.recentEvents.addLast(
                        Event(
                            ts = ev.optLong("ts", 0L),
                            comment = ev.optString("comment", "")
                        )
                    )
                }
            }
            return mem
        }

        private fun toStringList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) out.add(s)
            }
            return out
        }
    }
}

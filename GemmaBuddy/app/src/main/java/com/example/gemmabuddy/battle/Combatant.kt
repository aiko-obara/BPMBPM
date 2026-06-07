package com.example.gemmabuddy.battle

import kotlin.math.abs

/**
 * 戦闘参加者。Android 非依存の純データ。
 * ステータスは名前から決定的に導出するため、同じ相手は毎回同じ強さになる。
 */
data class Combatant(
    val name: String,
    val maxHp: Int,
    var hp: Int,
    val atk: Int,
    val def: Int,
    val spd: Int,
    var defending: Boolean = false
) {
    val isAlive: Boolean get() = hp > 0

    companion object {
        /** 名前ハッシュからステータスを決定的に算出する。 */
        fun fromName(name: String): Combatant {
            val seed = abs(name.hashCode().toLong())
            val maxHp = (60 + (seed % 41)).toInt()          // 60..100
            val atk = (12 + ((seed / 7) % 9)).toInt()       // 12..20
            val def = (6 + ((seed / 13) % 7)).toInt()       // 6..12
            val spd = (8 + ((seed / 17) % 9)).toInt()       // 8..16
            return Combatant(name, maxHp, maxHp, atk, def, spd)
        }
    }
}

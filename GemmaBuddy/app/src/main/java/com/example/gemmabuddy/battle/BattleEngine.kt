package com.example.gemmabuddy.battle

import kotlin.math.max
import kotlin.random.Random

/** プレイヤーが選べる行動。 */
enum class BattleAction { ATTACK, SKILL, DEFEND }

/** 戦闘の進行状態。 */
enum class BattleState { ONGOING, PLAYER_WIN, PLAYER_LOSE }

/** UI へ渡す1手の結果。アニメ再生はこのイベント列を順に処理する。 */
sealed class BattleEvent {
    /** [byPlayer] が攻撃して [amount] のダメージ。skill/crit フラグ付き。 */
    data class Damage(
        val byPlayer: Boolean,
        val amount: Int,
        val crit: Boolean,
        val skill: Boolean
    ) : BattleEvent()

    data class Defend(val byPlayer: Boolean) : BattleEvent()

    data class Defeated(val isPlayer: Boolean) : BattleEvent()
}

/**
 * ターン制バトルのコアロジック。Android 非依存・[rng] 注入でテスト可能。
 * 1ラウンド = プレイヤー行動 → （生存時）敵行動。
 */
class BattleEngine(
    val player: Combatant,
    val enemy: Combatant,
    private val rng: Random = Random.Default
) {
    var state: BattleState = BattleState.ONGOING
        private set

    /** プレイヤーの行動を解決し、発生イベント列を返す。 */
    fun playerTurn(action: BattleAction): List<BattleEvent> {
        if (state != BattleState.ONGOING) return emptyList()
        val events = mutableListOf<BattleEvent>()
        player.defending = false
        when (action) {
            BattleAction.DEFEND -> {
                player.defending = true
                events += BattleEvent.Defend(byPlayer = true)
            }
            BattleAction.ATTACK -> events += resolveAttack(player, enemy, byPlayer = true, skill = false)
            BattleAction.SKILL -> events += resolveAttack(player, enemy, byPlayer = true, skill = true)
        }
        if (!enemy.isAlive) {
            events += BattleEvent.Defeated(isPlayer = false)
            state = BattleState.PLAYER_WIN
        }
        return events
    }

    /** 敵の行動を解決する。簡易 AI: HP が低いと一定確率で防御、それ以外は通常/スキルを確率選択。 */
    fun enemyTurn(): List<BattleEvent> {
        if (state != BattleState.ONGOING) return emptyList()
        val events = mutableListOf<BattleEvent>()
        enemy.defending = false
        val lowHp = enemy.hp <= enemy.maxHp / 4
        val action = when {
            lowHp && rng.nextInt(100) < 40 -> BattleAction.DEFEND
            rng.nextInt(100) < 30 -> BattleAction.SKILL
            else -> BattleAction.ATTACK
        }
        when (action) {
            BattleAction.DEFEND -> {
                enemy.defending = true
                events += BattleEvent.Defend(byPlayer = false)
            }
            BattleAction.ATTACK -> events += resolveAttack(enemy, player, byPlayer = false, skill = false)
            BattleAction.SKILL -> events += resolveAttack(enemy, player, byPlayer = false, skill = true)
        }
        if (!player.isAlive) {
            events += BattleEvent.Defeated(isPlayer = true)
            state = BattleState.PLAYER_LOSE
        }
        return events
    }

    private fun resolveAttack(
        attacker: Combatant,
        target: Combatant,
        byPlayer: Boolean,
        skill: Boolean
    ): BattleEvent {
        // 基礎ダメージ: max(1, atk*2 - def)
        var dmg = max(1, attacker.atk * 2 - target.def).toDouble()
        if (skill) dmg *= SKILL_MULTIPLIER
        val crit = rng.nextInt(100) < CRIT_CHANCE_PCT
        if (crit) dmg *= CRIT_MULTIPLIER
        // ばらつき 0.85..1.15
        dmg *= 0.85 + rng.nextDouble() * 0.30
        if (target.defending) {
            dmg *= DEFEND_REDUCTION
            target.defending = false
        }
        val amount = max(1, dmg.toInt())
        target.hp = max(0, target.hp - amount)
        return BattleEvent.Damage(byPlayer = byPlayer, amount = amount, crit = crit, skill = skill)
    }

    companion object {
        const val SKILL_MULTIPLIER = 1.5
        const val CRIT_CHANCE_PCT = 12
        const val CRIT_MULTIPLIER = 1.8
        const val DEFEND_REDUCTION = 0.5
    }
}

package com.example.gemmabuddy.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BattleEngineTest {

    private fun player() = Combatant("P", maxHp = 100, hp = 100, atk = 20, def = 8, spd = 10)
    private fun enemy(hp: Int = 100) = Combatant("E", maxHp = hp, hp = hp, atk = 15, def = 6, spd = 10)

    private fun firstDamage(events: List<BattleEvent>): Int =
        (events.first { it is BattleEvent.Damage } as BattleEvent.Damage).amount

    @Test
    fun attack_dealsPositiveDamage_andDecrementsHp() {
        val e = BattleEngine(player(), enemy(), Random(1))
        val before = e.enemy.hp
        val dmg = firstDamage(e.playerTurn(BattleAction.ATTACK))
        assertTrue("damage must be positive", dmg >= 1)
        assertEquals(before - dmg, e.enemy.hp)
    }

    @Test
    fun skill_isStrongerThanAttack_forSameSeed() {
        val atk = firstDamage(BattleEngine(player(), enemy(), Random(42)).playerTurn(BattleAction.ATTACK))
        val skill = firstDamage(BattleEngine(player(), enemy(), Random(42)).playerTurn(BattleAction.SKILL))
        assertTrue("skill ($skill) should exceed attack ($atk)", skill > atk)
    }

    @Test
    fun defending_reducesIncomingDamage_forSameSeed() {
        val normal = firstDamage(BattleEngine(player(), enemy(), Random(7)).playerTurn(BattleAction.ATTACK))
        val defendedEnemy = enemy().apply { defending = true }
        val reduced = firstDamage(BattleEngine(player(), defendedEnemy, Random(7)).playerTurn(BattleAction.ATTACK))
        assertTrue("defended ($reduced) should be less than normal ($normal)", reduced < normal)
    }

    @Test
    fun playerWins_whenEnemyHpReachesZero() {
        val e = BattleEngine(player(), enemy(hp = 1), Random(3))
        val events = e.playerTurn(BattleAction.ATTACK)
        assertEquals(BattleState.PLAYER_WIN, e.state)
        assertTrue(events.any { it is BattleEvent.Defeated && !it.isPlayer })
    }

    @Test
    fun playerLoses_whenPlayerHpReachesZero() {
        val frailPlayer = Combatant("P", maxHp = 1, hp = 1, atk = 20, def = 8, spd = 10)
        val e = BattleEngine(frailPlayer, enemy(), Random(5))
        var rounds = 0
        while (e.state == BattleState.ONGOING && rounds < 50) {
            e.enemyTurn()
            rounds++
        }
        assertEquals(BattleState.PLAYER_LOSE, e.state)
    }

    @Test
    fun noEventsAfterBattleEnds() {
        val e = BattleEngine(player(), enemy(hp = 1), Random(3))
        e.playerTurn(BattleAction.ATTACK)
        assertTrue(e.state != BattleState.ONGOING)
        assertTrue(e.enemyTurn().isEmpty())
        assertTrue(e.playerTurn(BattleAction.ATTACK).isEmpty())
    }
}

package com.example.gemmabuddy

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gemmabuddy.battle.BattleAction
import com.example.gemmabuddy.battle.BattleEngine
import com.example.gemmabuddy.battle.BattleEvent
import com.example.gemmabuddy.battle.BattleState
import com.example.gemmabuddy.battle.Combatant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.droidsonroids.gif.AnimationListener
import pl.droidsonroids.gif.GifDrawable
import java.io.File

/**
 * ターン制バトル画面。アクティブバディ vs ランダムな既存キャラ。
 * 戦闘ロジックは [BattleEngine]（Android 非依存）に委譲し、本画面は描画/アニメ/入力を担う。
 * Gemma エンジンには触れない（オーバーレイサービスのモデルと二重起動しないため）。
 */
class BattleActivity : AppCompatActivity() {

    private lateinit var engine: BattleEngine
    private lateinit var memoryStore: MemoryStore

    private lateinit var ivPlayer: ImageView
    private lateinit var ivEnemy: ImageView
    private lateinit var pbPlayerHp: ProgressBar
    private lateinit var pbEnemyHp: ProgressBar
    private lateinit var tvPlayerName: TextView
    private lateinit var tvEnemyName: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvPlayerDamage: TextView
    private lateinit var tvEnemyDamage: TextView
    private lateinit var btnAttack: Button
    private lateinit var btnSkill: Button
    private lateinit var btnDefend: Button
    private lateinit var transitionView: BattleTransitionView
    private lateinit var ivEffect: ImageView

    private var playerIdle: List<Bitmap> = emptyList()
    private var playerSpeaking: Bitmap? = null
    private var enemyIdle: List<Bitmap> = emptyList()
    private var enemySpeaking: Bitmap? = null
    private var enemyName = "ENEMY"
    private var playerName = "BUDDY"

    private val handler = Handler(Looper.getMainLooper())
    private var frameTick = 0
    private var busy = false
    private var playerAttacking = false
    private var enemyAttacking = false

    private val idleCycler = object : Runnable {
        override fun run() {
            frameTick++
            if (!playerAttacking && playerIdle.size > 1) {
                ivPlayer.setImageBitmap(playerIdle[frameTick % playerIdle.size])
            }
            if (!enemyAttacking && enemyIdle.size > 1) {
                ivEnemy.setImageBitmap(enemyIdle[frameTick % enemyIdle.size])
            }
            handler.postDelayed(this, IDLE_FRAME_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_battle)
        memoryStore = MemoryStore(this)
        bindViews()

        if (!loadCombatantSprites()) {
            Toast.makeText(this, R.string.toast_need_buddy, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, CharacterGenerationActivity::class.java))
            finish()
            return
        }

        val player = Combatant.fromName(playerName)
        val enemy = Combatant.fromName(enemyName)
        engine = BattleEngine(player, enemy)

        tvPlayerName.text = playerName
        tvEnemyName.text = enemyName
        pbPlayerHp.max = player.maxHp; pbPlayerHp.progress = player.hp
        pbEnemyHp.max = enemy.maxHp; pbEnemyHp.progress = enemy.hp
        ivPlayer.setImageBitmap(playerIdle.firstOrNull())
        ivEnemy.setImageBitmap(enemyIdle.firstOrNull())
        tvLog.text = getString(R.string.battle_log_start, enemyName)

        btnAttack.setOnClickListener { onCommand(BattleAction.ATTACK) }
        btnSkill.setOnClickListener { onCommand(BattleAction.SKILL) }
        btnDefend.setOnClickListener { onCommand(BattleAction.DEFEND) }

        handler.postDelayed(idleCycler, IDLE_FRAME_MS)

        // 黒からのリビール演出（オーバーレイ側 cover と同じパターンで開く）
        playRevealTransition()
    }

    private fun playRevealTransition() {
        val pattern = runCatching {
            BattleTransitionView.Pattern.valueOf(
                intent.getStringExtra(EXTRA_PATTERN) ?: BattleTransitionView.Pattern.SPIRAL.name
            )
        }.getOrDefault(BattleTransitionView.Pattern.SPIRAL)

        transitionView.setFullyCovered()   // 開始時は全面黒
        setButtonsEnabled(false)
        // 着地の迫力として軽い画面シェイク
        transitionView.postDelayed({
            shake(findViewById(android.R.id.content))
            transitionView.play(pattern, BattleTransitionView.Mode.REVEAL, 600L) {
                transitionView.visibility = android.view.View.GONE
                setButtonsEnabled(true)
            }
        }, 120)
    }

    private fun shake(v: android.view.View) {
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            addUpdateListener {
                val p = it.animatedValue as Float
                val amp = 14f * (1f - p)
                v.translationX = ((Math.random() - 0.5) * 2 * amp).toFloat()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { v.translationX = 0f }
            })
            start()
        }
    }

    private fun bindViews() {
        ivPlayer = findViewById(R.id.iv_player)
        ivEnemy = findViewById(R.id.iv_enemy)
        pbPlayerHp = findViewById(R.id.pb_player_hp)
        pbEnemyHp = findViewById(R.id.pb_enemy_hp)
        tvPlayerName = findViewById(R.id.tv_player_name)
        tvEnemyName = findViewById(R.id.tv_enemy_name)
        tvLog = findViewById(R.id.tv_battle_log)
        tvPlayerDamage = findViewById(R.id.tv_player_damage)
        tvEnemyDamage = findViewById(R.id.tv_enemy_damage)
        btnAttack = findViewById(R.id.btn_attack)
        btnSkill = findViewById(R.id.btn_skill)
        btnDefend = findViewById(R.id.btn_defend)
        transitionView = findViewById(R.id.battle_transition)
        ivEffect = findViewById(R.id.iv_effect)
    }

    /** プレイヤー（filesDir のアクティブバディ）と敵（ランダムなアセットキャラ）の絵を読み込む。 */
    private fun loadCombatantSprites(): Boolean {
        val normal1 = File(filesDir, OverlayService.CUSTOM_CHAR_FILE)
            .takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) } ?: return false
        val normal2 = File(filesDir, BuddyStore.CUSTOM_CHAR_NORMAL2_FILE)
            .takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        playerSpeaking = File(filesDir, BuddyStore.CUSTOM_CHAR_SPEAKING_FILE)
            .takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        playerIdle = listOfNotNull(normal1, normal2)
        playerName = BuddyStore(this).getActive()?.name ?: getString(R.string.app_name)

        val provider = BuddyImageProvider(this)
        val folder = provider.listFolders().randomOrNull() ?: return false
        val frames = provider.loadFrames(folder) ?: return false
        enemyIdle = listOfNotNull(frames.normal1, frames.normal2)
        enemySpeaking = frames.speaking
        enemyName = BuddyImageProvider.displayName(folder)
        return true
    }

    private fun onCommand(action: BattleAction) {
        if (busy || engine.state != BattleState.ONGOING) return
        setButtonsEnabled(false)
        busy = true
        lifecycleScope.launch {
            playEvents(engine.playerTurn(action))
            if (engine.state == BattleState.ONGOING) {
                delay(350)
                playEvents(engine.enemyTurn())
            }
            if (engine.state != BattleState.ONGOING) {
                finishBattle()
            } else {
                busy = false
                setButtonsEnabled(true)
            }
        }
    }

    private suspend fun playEvents(events: List<BattleEvent>) {
        for (e in events) {
            when (e) {
                is BattleEvent.Damage -> {
                    animateAttack(e.byPlayer)
                    delay(160)
                    applyDamage(e)
                    delay(360)
                }
                is BattleEvent.Defend -> {
                    val who = if (e.byPlayer) playerName else enemyName
                    tvLog.text = getString(R.string.battle_log_defend, who)
                    delay(450)
                }
                is BattleEvent.Defeated -> {
                    val iv = if (e.isPlayer) ivPlayer else ivEnemy
                    iv.animate().alpha(0f).setDuration(500).start()
                    val who = if (e.isPlayer) playerName else enemyName
                    tvLog.text = getString(R.string.battle_log_defeated, who)
                    delay(600)
                }
            }
        }
    }

    /**
     * 命中点に 8bit ヒットエフェクト GIF（assets/effects/hit_spark.gif）を1回再生する。
     * 素材が未配置でも安全に no-op（外部ツールで生成した GIF を後から差し込めば有効化）。
     */
    private fun playHitSpark(targetIv: ImageView) {
        val gif = try {
            GifDrawable(assets, "effects/hit_spark.gif")
        } catch (e: Exception) {
            return  // 素材未配置
        }
        gif.loopCount = 1
        val size = if (ivEffect.width > 0) ivEffect.width
        else (140 * resources.displayMetrics.density).toInt()
        ivEffect.x = targetIv.x + targetIv.width / 2f - size / 2f
        ivEffect.y = targetIv.y + targetIv.height / 2f - size / 2f
        ivEffect.setImageDrawable(gif)
        ivEffect.visibility = ImageView.VISIBLE
        gif.addAnimationListener(object : AnimationListener {
            override fun onAnimationCompleted(loopNumber: Int) {
                ivEffect.visibility = ImageView.INVISIBLE
                ivEffect.setImageDrawable(null)
                gif.recycle()
            }
        })
        gif.start()
    }

    private fun animateAttack(byPlayer: Boolean) {
        val attacker = if (byPlayer) ivPlayer else ivEnemy
        if (byPlayer) playerAttacking = true else enemyAttacking = true
        (if (byPlayer) playerSpeaking else enemySpeaking)?.let { attacker.setImageBitmap(it) }
        val lunge = if (byPlayer) 60f else -60f
        ObjectAnimator.ofFloat(attacker, "translationX", 0f, lunge, 0f).apply {
            duration = 320
            start()
        }
        attacker.postDelayed({
            if (byPlayer) playerAttacking = false else enemyAttacking = false
            val idle = if (byPlayer) playerIdle else enemyIdle
            idle.firstOrNull()?.let { attacker.setImageBitmap(it) }
        }, 340)
    }

    private fun applyDamage(e: BattleEvent.Damage) {
        // e.byPlayer == true の被害者は敵
        val targetIsEnemy = e.byPlayer
        val targetIv = if (targetIsEnemy) ivEnemy else ivPlayer
        val dmgTv = if (targetIsEnemy) tvEnemyDamage else tvPlayerDamage
        val hpBar = if (targetIsEnemy) pbEnemyHp else pbPlayerHp
        val attacker = if (e.byPlayer) playerName else enemyName

        // HP バー更新
        hpBar.progress = if (targetIsEnemy) engine.enemy.hp else engine.player.hp

        // ログ
        val verb = if (e.skill) getString(R.string.battle_log_skill, attacker)
        else getString(R.string.battle_log_attack, attacker)
        val dmgLine = if (e.crit) getString(R.string.battle_log_crit, e.amount)
        else getString(R.string.battle_log_damage, e.amount)
        tvLog.text = "$verb  $dmgLine"

        // 着弾エフェクト（外部生成の透過GIF）。素材が無ければ何も起きない
        playHitSpark(targetIv)

        // 被弾フラッシュ＋シェイク
        targetIv.setColorFilter(Color.argb(160, 255, 40, 40))
        targetIv.postDelayed({ targetIv.clearColorFilter() }, 150)
        ObjectAnimator.ofFloat(targetIv, "translationX", 0f, -16f, 16f, -10f, 10f, 0f).apply {
            duration = 260; start()
        }

        // ダメージ数値フロート
        dmgTv.text = e.amount.toString()
        dmgTv.alpha = 1f
        dmgTv.translationY = 0f
        dmgTv.visibility = TextView.VISIBLE
        dmgTv.animate().translationY(-60f).alpha(0f).setDuration(700).start()
    }

    private fun finishBattle() {
        handler.removeCallbacks(idleCycler)
        val win = engine.state == BattleState.PLAYER_WIN
        recordResult(win)
        val titleRes = if (win) R.string.battle_result_win else R.string.battle_result_lose
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setCancelable(false)
            .setPositiveButton(R.string.battle_rematch) { _, _ -> recreate() }
            .setNegativeButton(R.string.battle_close) { _, _ -> finish() }
            .show()
    }

    /** 勝敗を記憶へ残す。完勝（HP 半分以上残し勝利）は大切な思い出にも。 */
    private fun recordResult(win: Boolean) {
        val text = if (win) "バトル: $enemyName に勝利した" else "バトル: $enemyName に敗北した"
        try {
            val mem = memoryStore.load()
            mem.addEvent(text)
            memoryStore.save(mem)
            if (win && engine.player.hp >= engine.player.maxHp / 2) {
                memoryStore.addImportantMemory("$enemyName との戦いに快勝した")
            }
        } catch (_: Exception) {
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnAttack.isEnabled = enabled
        btnSkill.isEnabled = enabled
        btnDefend.isEnabled = enabled
    }

    override fun onStart() {
        super.onStart()
        // バトル中は前面のオーバーレイキャラを隠す
        sendBroadcast(Intent(OverlayService.ACTION_HIDE_CHARACTER).apply { `package` = packageName })
    }

    override fun onStop() {
        super.onStop()
        // バトルを離れたらオーバーレイキャラを再表示
        sendBroadcast(Intent(OverlayService.ACTION_SHOW_CHARACTER).apply { `package` = packageName })
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(idleCycler)
    }

    companion object {
        private const val IDLE_FRAME_MS = 400L
        const val EXTRA_PATTERN = "battle_transition_pattern"
    }
}

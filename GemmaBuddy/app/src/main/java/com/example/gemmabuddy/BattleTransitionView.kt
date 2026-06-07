package com.example.gemmabuddy

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * バトル遷移のレトロ演出を描画する全画面ビュー。
 * 粗いブロック格子で「スパイラル渦」または「アイリス（円）」のワイプを行う。
 *
 * - COVER : 透明（遷移元が見える）→ 黒（暗転）
 * - REVEAL: 黒 → 透明（背後の戦闘画面が出現）
 *
 * 角丸・アンチエイリアスなしでドット感のある 8/16bit 風の見た目にする。
 */
class BattleTransitionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Pattern { SPIRAL, IRIS }
    enum class Mode { COVER, REVEAL }

    private val blackPaint = Paint().apply { color = Color.BLACK; isAntiAlias = false }

    private var pattern = Pattern.SPIRAL
    private var mode = Mode.REVEAL
    private var progress = 0f

    private var cols = 0
    private var rows = 0
    private var cellSize = 0f
    private var maxRadius = 1f
    // SPIRAL の各セル閾値（行優先 rows*cols）
    private var thresholds: FloatArray = FloatArray(0)

    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        cellSize = w / COLS.toFloat()
        cols = COLS
        rows = (h / cellSize).toInt() + 1
        val cxp = w / 2f
        val cyp = h / 2f
        maxRadius = hypot(cxp, cyp)
        thresholds = FloatArray(rows * cols)
        val spiralTurns = 2f
        val norm = 1f + 1f / spiralTurns
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val ccx = c * cellSize + cellSize / 2f
                val ccy = r * cellSize + cellSize / 2f
                val dist = hypot(ccx - cxp, ccy - cyp) / maxRadius
                var ang = atan2(ccy - cyp, ccx - cxp)               // -π..π
                if (ang < 0) ang += (2.0 * Math.PI).toFloat()
                val angFrac = (ang / (2.0 * Math.PI)).toFloat()      // 0..1
                thresholds[r * cols + c] = ((dist + angFrac / spiralTurns) / norm).coerceIn(0f, 1f)
            }
        }
    }

    /** 演出を再生する。[onEnd] は完了時に呼ばれる。 */
    fun play(pattern: Pattern, mode: Mode, durationMs: Long = 650L, onEnd: () -> Unit = {}) {
        this.pattern = pattern
        this.mode = mode
        progress = 0f
        visibility = VISIBLE
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = if (mode == Mode.COVER) AccelerateInterpolator(1.4f)
            else DecelerateInterpolator(1.4f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { onEnd() }
            })
            start()
        }
    }

    /** 全面黒で固定（リビール前の初期カバー用）。 */
    fun setFullyCovered() {
        animator?.cancel()
        mode = Mode.REVEAL
        progress = 0f
        visibility = VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // 未計測時は全面黒で確実にカバー
        if (cols == 0 || thresholds.isEmpty()) {
            canvas.drawColor(Color.BLACK)
            return
        }
        when (pattern) {
            Pattern.SPIRAL -> drawSpiral(canvas)
            Pattern.IRIS -> drawIris(canvas)
        }
    }

    private fun drawSpiral(canvas: Canvas) {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val t = thresholds[r * cols + c]
                val black = if (mode == Mode.COVER) progress >= t else progress < t
                if (black) drawCell(canvas, c, r)
            }
        }
    }

    private fun drawIris(canvas: Canvas) {
        val radius = if (mode == Mode.COVER) maxRadius * (1f - progress) else maxRadius * progress
        val cxp = width / 2f
        val cyp = height / 2f
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val ccx = c * cellSize + cellSize / 2f
                val ccy = r * cellSize + cellSize / 2f
                if (hypot(ccx - cxp, ccy - cyp) > radius) drawCell(canvas, c, r)
            }
        }
    }

    private fun drawCell(canvas: Canvas, c: Int, r: Int) {
        val left = c * cellSize
        val top = r * cellSize
        canvas.drawRect(left, top, left + cellSize + 1f, top + cellSize + 1f, blackPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    companion object {
        private const val COLS = 18
    }
}

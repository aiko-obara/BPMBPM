package com.example.gemmabuddy

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View

class SpeechBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Neo-Famicom HUD スタイル ──────────────────────────────

    /** 半透明ダーク背景 rgba(17,19,22,0.92) */
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EB111316")
        style = Paint.Style.FILL
    }

    /** シアン枠線 */
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#92ccff")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    /** シアン発光（グロー）*/
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3392ccff")   // ~20% alpha cyan
        style = Paint.Style.STROKE
        strokeWidth = 12f
    }

    /** 上部 2dp アクセントバー */
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#92ccff")
        style = Paint.Style.FILL
    }

    /** ラベル「◉ GEMMA」用ペイント */
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#92ccff")
        style = Paint.Style.FILL
        textSize = 24f
        isAntiAlias = true
    }

    // メッセージ本文ペイント
    private val misakiTypeface: Typeface by lazy {
        try {
            Typeface.createFromAsset(context.assets, "fonts/misaki_gothic.ttf")
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#e2e2e6")
        textSize = 32f
    }

    init {
        post {
            textPaint.typeface = misakiTypeface
            labelPaint.typeface = misakiTypeface
        }
    }

    private var buddyName = "BUDDY"

    fun setStyle(accentHex: String, name: String) {
        val rgb = android.graphics.Color.parseColor(accentHex)
        val r = android.graphics.Color.red(rgb)
        val g = android.graphics.Color.green(rgb)
        val b = android.graphics.Color.blue(rgb)
        borderPaint.color = rgb
        glowPaint.color = android.graphics.Color.argb(0x33, r, g, b)
        accentPaint.color = rgb
        labelPaint.color = rgb
        buddyName = name.uppercase()
        invalidate()
    }

    private var text = ""
    private val padding = 24f
    private val labelHeight = 32f   // ラベル行の高さ
    private val tailHeight = 20f
    private val cornerRadius = 14f
    private val accentBarH = 3f

    // ── タイプライター ─────────────────────────────────────────

    private var fullText = ""
    private var charIndex = 0
    private val typewriterHandler = Handler(Looper.getMainLooper())

    private val charDelayMs: Long
        get() {
            val prefs = context.getSharedPreferences(
                "gemmabuddy_prefs", android.content.Context.MODE_PRIVATE
            )
            return prefs.getString("typewriter_speed", "60")?.toLongOrNull() ?: 60L
        }

    fun showText(message: String) {
        typewriterHandler.removeCallbacksAndMessages(null)
        fullText = message
        charIndex = 0
        text = ""
        visibility = VISIBLE
        alpha = 1f
        scheduleNextChar()
    }

    private fun scheduleNextChar() {
        typewriterHandler.postDelayed({
            if (charIndex <= fullText.length) {
                text = fullText.substring(0, charIndex)
                charIndex++
                requestLayout()
                invalidate()
                if (charIndex <= fullText.length) {
                    scheduleNextChar()
                } else {
                    onTypewriterDoneListener?.invoke()
                    scheduleHide()
                }
            }
        }, charDelayMs)
    }

    var onHideListener: (() -> Unit)? = null
    var onTypewriterDoneListener: (() -> Unit)? = null

    private fun scheduleHide() {
        typewriterHandler.postDelayed({
            ObjectAnimator.ofFloat(this, "alpha", 1f, 0f).apply {
                duration = 600
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        visibility = GONE
                        onHideListener?.invoke()
                    }
                })
                start()
            }
        }, 8000)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        typewriterHandler.removeCallbacksAndMessages(null)
    }

    // ── レイアウト ────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = 560f
        val layout = makeLayout(maxW)
        val w = (layout.width + padding * 2).toInt().coerceAtLeast(160)
        val h = (layout.height + padding * 2 + labelHeight + tailHeight).toInt()
        setMeasuredDimension(w, h)
    }

    // ── 描画 ──────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val bubbleH = h - tailHeight

        val rect = RectF(4f, 4f, w - 4f, bubbleH - 4f)

        // 1. グロー
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, glowPaint)

        // 2. 背景
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

        // 3. 上部アクセントバー（角丸部分に被せる）
        val accentRect = RectF(4f, 4f, w - 4f, 4f + accentBarH + cornerRadius)
        canvas.drawRoundRect(accentRect, cornerRadius, cornerRadius, accentPaint)
        // 下半分を背景色で塗りつぶして直線に見せる
        canvas.drawRect(4f, 4f + cornerRadius, w - 4f, 4f + accentBarH + cornerRadius, accentPaint)

        // 4. 枠線
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        // 5. しっぽ
        val tailX = w / 2f
        val tailPath = Path().apply {
            moveTo(tailX - 14f, bubbleH - 2f)
            lineTo(tailX + 14f, bubbleH - 2f)
            lineTo(tailX, h - 2f)
            close()
        }
        canvas.drawPath(tailPath, bgPaint)
        canvas.drawPath(tailPath, borderPaint)

        // 6. ラベル「◉ [名前]」
        val labelY = 4f + accentBarH + 8f + labelPaint.textSize
        canvas.drawText("◉ $buddyName", padding, labelY, labelPaint)

        // 7. テキスト本文
        val textTop = 4f + accentBarH + labelHeight + padding * 0.5f
        canvas.save()
        canvas.translate(padding, textTop)
        makeLayout(w - padding * 2).draw(canvas)
        canvas.restore()
    }

    private fun makeLayout(maxWidth: Float): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, maxWidth.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.3f)
            .build()
    }
}

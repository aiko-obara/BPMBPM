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

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFF0")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val misakiTypeface: Typeface by lazy {
        try {
            Typeface.createFromAsset(context.assets, "fonts/misaki_gothic.ttf")
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222")
        textSize = 32f
    }

    init {
        post { textPaint.typeface = misakiTypeface }
    }

    private var text = ""
    private val padding = 24f
    private val tailHeight = 24f
    private val cornerRadius = 16f

    // タイプライター用
    private var fullText = ""
    private var charIndex = 0
    private val typewriterHandler = Handler(Looper.getMainLooper())
    private val CHAR_DELAY_MS = 60L

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
                    scheduleHide()
                }
            }
        }, CHAR_DELAY_MS)
    }

    private fun scheduleHide() {
        typewriterHandler.postDelayed({
            ObjectAnimator.ofFloat(this, "alpha", 1f, 0f).apply {
                duration = 500
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        visibility = GONE
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = 600f
        val layout = makeLayout(maxW)
        val w = (layout.width + padding * 2).toInt().coerceAtLeast(100)
        val h = (layout.height + padding * 2 + tailHeight).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val bubbleH = h - tailHeight

        val rect = RectF(2f, 2f, w - 2f, bubbleH - 2f)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        val tailX = w / 2f
        val path = Path().apply {
            moveTo(tailX - 16f, bubbleH - 2f)
            lineTo(tailX + 16f, bubbleH - 2f)
            lineTo(tailX, h - 2f)
            close()
        }
        canvas.drawPath(path, bgPaint)
        canvas.drawPath(path, borderPaint)

        canvas.save()
        canvas.translate(padding, padding)
        makeLayout(w - padding * 2).draw(canvas)
        canvas.restore()
    }

    private fun makeLayout(maxWidth: Float): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, maxWidth.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.2f)
            .build()
    }
}

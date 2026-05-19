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
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222")
        textSize = 36f
    }

    private var text = ""
    private val padding = 24f
    private val tailHeight = 24f
    private val cornerRadius = 16f

    fun showText(message: String) {
        text = message
        visibility = VISIBLE
        alpha = 0f
        ObjectAnimator.ofFloat(this, "alpha", 0f, 1f).apply {
            duration = 300
            start()
        }
        requestLayout()
        invalidate()

        // 8秒後に自動で非表示
        postDelayed({
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

        // 吹き出し本体（8bit風: 角丸四角）
        val rect = RectF(2f, 2f, w - 2f, bubbleH - 2f)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        // 吹き出しのしっぽ（下向き三角）
        val tailX = w / 2f
        val path = Path().apply {
            moveTo(tailX - 16f, bubbleH - 2f)
            lineTo(tailX + 16f, bubbleH - 2f)
            lineTo(tailX, h - 2f)
            close()
        }
        canvas.drawPath(path, bgPaint)
        canvas.drawPath(path, borderPaint)

        // テキスト描画
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

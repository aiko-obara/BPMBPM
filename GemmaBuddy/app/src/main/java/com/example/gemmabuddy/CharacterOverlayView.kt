package com.example.gemmabuddy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import pl.droidsonroids.gif.GifDrawable
import pl.droidsonroids.gif.GifImageView
import kotlin.math.abs

class CharacterOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val pixelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gifDrawable: GifDrawable? = null

    // 8bitキャラクターのピクセルマップ（デフォルト: シンプルな人型）
    private val pixelChar = arrayOf(
        intArrayOf(0, 1, 1, 0),
        intArrayOf(1, 1, 1, 1),
        intArrayOf(0, 1, 1, 0),
        intArrayOf(1, 0, 0, 1),
        intArrayOf(1, 0, 0, 1)
    )
    private val pixelSize = 20f
    private val charColor = Color.parseColor("#4CAF50")
    private val outlineColor = Color.parseColor("#1B5E20")

    var onMoveListener: ((dx: Float, dy: Float) -> Unit)? = null
    var onTapListener: (() -> Unit)? = null

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false

    fun setGifDrawable(drawable: GifDrawable) {
        gifDrawable = drawable
        drawable.callback = this
        drawable.start()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val gif = gifDrawable
        if (gif != null) {
            setMeasuredDimension(gif.intrinsicWidth, gif.intrinsicHeight)
        } else {
            val w = (pixelChar[0].size * pixelSize).toInt()
            val h = (pixelChar.size * pixelSize).toInt()
            setMeasuredDimension(w, h)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val gif = gifDrawable
        if (gif != null) {
            gif.setBounds(0, 0, width, height)
            gif.draw(canvas)
        } else {
            drawPixelCharacter(canvas)
        }
    }

    private fun drawPixelCharacter(canvas: Canvas) {
        for (row in pixelChar.indices) {
            for (col in pixelChar[row].indices) {
                if (pixelChar[row][col] == 1) {
                    val x = col * pixelSize
                    val y = row * pixelSize
                    pixelPaint.color = outlineColor
                    canvas.drawRect(x - 1f, y - 1f, x + pixelSize + 1f, y + pixelSize + 1f, pixelPaint)
                    pixelPaint.color = charColor
                    canvas.drawRect(x, y, x + pixelSize, y + pixelSize, pixelPaint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.rawX
                touchStartY = event.rawY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchStartX
                val dy = event.rawY - touchStartY
                if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) {
                    isDragging = true
                }
                if (isDragging) {
                    onMoveListener?.invoke(dx, dy)
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) onTapListener?.invoke()
                return true
            }
        }
        return false
    }

    override fun verifyDrawable(who: android.graphics.drawable.Drawable): Boolean {
        return who == gifDrawable || super.verifyDrawable(who)
    }
}

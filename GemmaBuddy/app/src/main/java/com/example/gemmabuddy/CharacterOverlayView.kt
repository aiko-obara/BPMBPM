package com.example.gemmabuddy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import pl.droidsonroids.gif.GifDrawable
import kotlin.math.abs

class CharacterOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val pixelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gifDrawable: GifDrawable? = null
    private var customBitmap: Bitmap? = null
    private var bobYOffset = 0f
    private val bobOffsets = floatArrayOf(0f, -3f, 0f, 3f)
    private var bobIndex = 0
    private val bobHandler = Handler(Looper.getMainLooper())
    private val bobRunnable = object : Runnable {
        override fun run() {
            bobIndex = (bobIndex + 1) % bobOffsets.size
            bobYOffset = bobOffsets[bobIndex]
            invalidate()
            bobHandler.postDelayed(this, 250)
        }
    }

    // デフォルト 8bit ピクセルキャラ
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
        bobHandler.removeCallbacks(bobRunnable)
        customBitmap = null
        gifDrawable = drawable
        drawable.callback = this
        drawable.start()
        invalidate()
        requestLayout()
    }

    fun setCustomBitmap(bitmap: Bitmap) {
        gifDrawable?.stop()
        gifDrawable = null
        customBitmap = Bitmap.createScaledBitmap(bitmap, CUSTOM_CHAR_SIZE, CUSTOM_CHAR_SIZE, false)
        bobHandler.removeCallbacks(bobRunnable)
        bobHandler.post(bobRunnable)
        invalidate()
        requestLayout()
    }

    fun clearCustomBitmap() {
        bobHandler.removeCallbacks(bobRunnable)
        customBitmap = null
        invalidate()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        when {
            customBitmap != null -> setMeasuredDimension(CUSTOM_CHAR_SIZE, CUSTOM_CHAR_SIZE)
            gifDrawable != null -> setMeasuredDimension(
                gifDrawable!!.intrinsicWidth, gifDrawable!!.intrinsicHeight
            )
            else -> setMeasuredDimension(
                (pixelChar[0].size * pixelSize).toInt(),
                (pixelChar.size * pixelSize).toInt()
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        when {
            customBitmap != null -> canvas.drawBitmap(customBitmap!!, 0f, bobYOffset, null)
            gifDrawable != null -> {
                gifDrawable!!.setBounds(0, 0, width, height)
                gifDrawable!!.draw(canvas)
            }
            else -> drawPixelCharacter(canvas)
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bobHandler.removeCallbacks(bobRunnable)
    }

    companion object {
        const val CUSTOM_CHAR_SIZE = 128
    }
}

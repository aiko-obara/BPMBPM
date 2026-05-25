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

    // マルチフレーム
    private var normalFrames: List<Bitmap> = emptyList()
    private var speakingFrame: Bitmap? = null
    private var isSpeaking = false
    private var frameIndex = 0

    private var bobYOffset = 0f
    private var bobAmplitude = 3f
    private var bobIntervalMs = 250L
    private var bobIndex = 0
    private val bobHandler = Handler(Looper.getMainLooper())

    private var escapeFrames: List<Bitmap> = emptyList()
    private var escapeFrameIndex = 0
    private var onEscapeComplete: (() -> Unit)? = null
    private var isEscaping = false
    private val ESCAPE_TOTAL_CYCLES = 6
    private val ESCAPE_FRAME_MS = 200L

    private val escapeRunnable = object : Runnable {
        override fun run() {
            if (escapeFrames.isEmpty()) return
            customBitmap = escapeFrames[escapeFrameIndex % escapeFrames.size]
            escapeFrameIndex++
            invalidate()
            requestLayout()
            if (escapeFrameIndex < escapeFrames.size * ESCAPE_TOTAL_CYCLES) {
                bobHandler.postDelayed(this, ESCAPE_FRAME_MS)
            } else {
                isEscaping = false
                onEscapeComplete?.invoke()
            }
        }
    }

    fun playEscapeAnimation(frames: List<Bitmap>, onComplete: () -> Unit) {
        bobHandler.removeCallbacks(bobRunnable)
        bobHandler.removeCallbacks(escapeRunnable)
        escapeFrames = frames.map { scaleToFit(it) }
        escapeFrameIndex = 0
        onEscapeComplete = onComplete
        isEscaping = true
        bobHandler.post(escapeRunnable)
    }
    private val bobRunnable = object : Runnable {
        override fun run() {
            bobIndex = (bobIndex + 1) % 4
            bobYOffset = when (bobIndex) {
                1 -> -bobAmplitude
                3 ->  bobAmplitude
                else -> 0f
            }
            if (bobIndex == 0 && normalFrames.size > 1) {
                frameIndex = (frameIndex + 1) % normalFrames.size
            }
            invalidate()
            bobHandler.postDelayed(this, bobIntervalMs)
        }
    }

    fun setEmotion(emotion: BuddyEmotion) {
        bobAmplitude = emotion.bobAmplitude
        bobIntervalMs = emotion.bobIntervalMs
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
    var onLongPressListener: (() -> Unit)? = null

    private val longPressRunnable = Runnable { onLongPressListener?.invoke() }

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
        normalFrames = emptyList()
        speakingFrame = null
        customBitmap = scaleToFit(bitmap)
        frameIndex = 0
        isSpeaking = false
        bobHandler.removeCallbacks(bobRunnable)
        bobHandler.post(bobRunnable)
        invalidate()
        requestLayout()
    }

    /** 通常フレーム複数 + 発話フレームをセット */
    fun setFrames(normals: List<Bitmap>, speaking: Bitmap?) {
        gifDrawable?.stop()
        gifDrawable = null
        customBitmap = null
        normalFrames = normals.map { scaleToFit(it) }
        speakingFrame = speaking?.let { scaleToFit(it) }
        frameIndex = 0
        isSpeaking = false
        bobHandler.removeCallbacks(bobRunnable)
        bobHandler.post(bobRunnable)
        invalidate()
        requestLayout()
    }

    fun setSpeaking(speaking: Boolean) {
        if (isSpeaking == speaking) return
        isSpeaking = speaking
        invalidate()
    }

    fun clearCustomBitmap() {
        bobHandler.removeCallbacks(bobRunnable)
        customBitmap = null
        normalFrames = emptyList()
        speakingFrame = null
        invalidate()
        requestLayout()
    }

    private fun scaleToFit(bitmap: Bitmap): Bitmap {
        val scale = minOf(
            CUSTOM_CHAR_MAX_PX.toFloat() / bitmap.width,
            CUSTOM_CHAR_MAX_PX.toFloat() / bitmap.height
        )
        val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun currentBmp(): Bitmap? = when {
        isEscaping -> customBitmap
        isSpeaking && speakingFrame != null -> speakingFrame
        normalFrames.isNotEmpty() -> normalFrames[frameIndex]
        else -> customBitmap
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bmp = currentBmp()
        when {
            bmp != null -> setMeasuredDimension(bmp.width, bmp.height)
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
        val bmp = currentBmp()
        when {
            bmp != null -> canvas.drawBitmap(bmp, 0f, bobYOffset, null)
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
                bobHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchStartX
                val dy = event.rawY - touchStartY
                if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) {
                    isDragging = true
                    bobHandler.removeCallbacks(longPressRunnable)
                }
                if (isDragging) {
                    onMoveListener?.invoke(dx, dy)
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                bobHandler.removeCallbacks(longPressRunnable)
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
        bobHandler.removeCallbacks(longPressRunnable)
        bobHandler.removeCallbacks(escapeRunnable)
    }

    companion object {
        const val CUSTOM_CHAR_MAX_PX = 300
        const val LONG_PRESS_MS = 600L
    }
}

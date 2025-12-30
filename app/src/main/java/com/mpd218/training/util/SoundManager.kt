package com.mpd218.training.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.mpd218.training.model.HitFeedback

/**
 * Manages sound effects for feedback
 *
 * Note: This implementation uses ToneGenerator as a placeholder.
 * In production, replace with actual audio files in res/raw/
 */
class SoundManager(private val context: Context) {

    private val soundPool: SoundPool
    private val soundIds = mutableMapOf<String, Int>()

    init {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(audioAttributes)
            .build()

        // TODO: Load actual sound files from res/raw/
        // soundIds["perfect"] = soundPool.load(context, R.raw.perfect_sound, 1)
        // soundIds["good"] = soundPool.load(context, R.raw.good_sound, 1)
        // soundIds["miss"] = soundPool.load(context, R.raw.miss_sound, 1)
        // soundIds["result"] = soundPool.load(context, R.raw.result_sound, 1)
    }

    fun playFeedback(feedback: HitFeedback) {
        val soundKey = when (feedback) {
            HitFeedback.PERFECT -> "perfect"
            HitFeedback.GOOD -> "good"
            HitFeedback.MISS -> "miss"
        }

        soundIds[soundKey]?.let { soundId ->
            soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
        }
    }

    fun playResultSound() {
        soundIds["result"]?.let { soundId ->
            soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
        }
    }

    fun release() {
        soundPool.release()
    }
}

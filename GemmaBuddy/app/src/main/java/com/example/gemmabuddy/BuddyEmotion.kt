package com.example.gemmabuddy

enum class BuddyEmotion(
    val accentColor: String,
    val bobAmplitude: Float,
    val bobIntervalMs: Long
) {
    HAPPY   ("#FFD700", 12f, 180L),
    EXCITED ("#FF5722", 16f, 130L),
    SAD     ("#90A4AE",  4f, 400L),
    ANGRY   ("#E53935",  6f, 140L),
    SLEEPY  ("#B39DDB",  3f, 600L),
    NEUTRAL ("#92ccff",  8f, 250L);

    companion object {
        fun fromTag(tag: String): BuddyEmotion =
            entries.find { it.name == tag.uppercase() } ?: NEUTRAL
    }
}

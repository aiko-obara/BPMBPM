package com.mpd218.training.model

/**
 * Application state
 */
sealed class AppScreen {
    data object Connection : AppScreen()
    data object Home : AppScreen()
    data object ModeSelect : AppScreen()
    data object Practice : AppScreen()
    data object Result : AppScreen()
}

/**
 * Practice mode
 */
enum class PracticeMode {
    TIMING,
    MEMORY
}

/**
 * Practice difficulty level
 */
enum class Level(val bpm: Int) {
    LEVEL_1(60),
    LEVEL_2(80),
    LEVEL_3(100),
    LEVEL_4(120),
    LEVEL_5(140)
}

/**
 * Feedback for individual pad hit
 */
enum class HitFeedback {
    PERFECT,    // ○
    GOOD,       // △
    MISS        // ×
}

/**
 * Practice session state
 */
data class PracticeState(
    val currentScreen: AppScreen = AppScreen.Connection,
    val isConnected: Boolean = false,
    val mode: PracticeMode? = null,
    val level: Level = Level.LEVEL_1,
    val targetPads: List<Int> = emptyList(),
    val currentTargetIndex: Int = 0,
    val userInputs: List<PadHit> = emptyList(),
    val countdown: Int? = null,
    val isActive: Boolean = false,
    val startTime: Long? = null
)

/**
 * Record of a pad hit
 */
data class PadHit(
    val padIndex: Int,
    val timestamp: Long,
    val timingErrorMs: Long? = null,  // For timing mode
    val feedback: HitFeedback
)

/**
 * Practice result
 */
data class PracticeResult(
    val mode: PracticeMode,
    val level: Level,
    val totalHits: Int,
    val perfectCount: Int,
    val goodCount: Int,
    val missCount: Int,
    val stars: Int  // 1-3
) {
    companion object {
        fun fromPracticeState(state: PracticeState): PracticeResult {
            val perfectCount = state.userInputs.count { it.feedback == HitFeedback.PERFECT }
            val goodCount = state.userInputs.count { it.feedback == HitFeedback.GOOD }
            val missCount = state.userInputs.count { it.feedback == HitFeedback.MISS }
            val totalHits = state.userInputs.size

            val successRate = if (totalHits > 0) {
                (perfectCount + goodCount).toFloat() / totalHits
            } else {
                0f
            }

            val stars = when {
                successRate >= 0.9f && perfectCount.toFloat() / totalHits >= 0.6f -> 3
                successRate >= 0.75f -> 2
                successRate >= 0.5f -> 1
                else -> 1
            }

            return PracticeResult(
                mode = state.mode ?: PracticeMode.TIMING,
                level = state.level,
                totalHits = totalHits,
                perfectCount = perfectCount,
                goodCount = goodCount,
                missCount = missCount,
                stars = stars
            )
        }
    }
}

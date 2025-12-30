package com.mpd218.training.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mpd218.training.model.HitFeedback
import com.mpd218.training.model.PracticeState

@Composable
fun PracticeScreen(
    state: PracticeState
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (state.countdown != null) {
            CountdownOverlay(countdown = state.countdown)
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PadGrid(
                    targetPad = if (state.currentTargetIndex >= 0 && state.currentTargetIndex < state.targetPads.size) {
                        state.targetPads[state.currentTargetIndex]
                    } else {
                        null
                    },
                    recentHit = state.userInputs.lastOrNull()
                )
            }
        }
    }
}

@Composable
fun CountdownOverlay(countdown: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "countdown")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Text(
        text = countdown.toString(),
        fontSize = 120.sp,
        modifier = Modifier.scale(scale)
    )
}

@Composable
fun PadGrid(
    targetPad: Int?,
    recentHit: com.mpd218.training.model.PadHit?
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (row in 0 until 4) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (col in 0 until 4) {
                    val padIndex = row * 4 + col
                    PadView(
                        index = padIndex,
                        isTarget = padIndex == targetPad,
                        feedback = if (recentHit?.padIndex == padIndex) recentHit.feedback else null
                    )
                }
            }
        }
    }
}

@Composable
fun PadView(
    index: Int,
    isTarget: Boolean,
    feedback: HitFeedback?
) {
    val backgroundColor = when {
        isTarget -> Color(0xFFFFEB3B)  // Bright yellow for target
        else -> Color(0xFF424242)  // Dark gray
    }

    val feedbackText = when (feedback) {
        HitFeedback.PERFECT -> "○"
        HitFeedback.GOOD -> "△"
        HitFeedback.MISS -> "×"
        null -> ""
    }

    val feedbackColor = when (feedback) {
        HitFeedback.PERFECT -> Color(0xFF4CAF50)  // Green
        HitFeedback.GOOD -> Color(0xFFFFC107)     // Amber
        HitFeedback.MISS -> Color(0xFFF44336)     // Red
        null -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .size(80.dp)
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .border(2.dp, Color(0xFF757575), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (feedbackText.isNotEmpty()) {
            Text(
                text = feedbackText,
                fontSize = 48.sp,
                color = feedbackColor
            )
        }
    }
}

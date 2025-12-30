package com.mpd218.training.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mpd218.training.model.PracticeResult

@Composable
fun ResultScreen(
    result: PracticeResult,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            AnimatedStars(stars = result.stars)

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .width(150.dp)
                        .height(70.dp)
                ) {
                    Text("つづける", fontSize = 24.sp)
                }

                Button(
                    onClick = onBack,
                    modifier = Modifier
                        .width(150.dp)
                        .height(70.dp)
                ) {
                    Text("もどる", fontSize = 24.sp)
                }
            }
        }
    }
}

@Composable
fun AnimatedStars(stars: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "stars")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(3) { index ->
            Text(
                text = if (index < stars) "★" else "☆",
                fontSize = 72.sp,
                modifier = if (index < stars) Modifier.scale(scale) else Modifier
            )
        }
    }
}

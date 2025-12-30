package com.mpd218.training.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConnectionScreen(
    isConnected: Boolean,
    onStart: () -> Unit,
    onScan: () -> Unit
) {
    LaunchedEffect(Unit) {
        onScan()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            if (!isConnected) {
                PulsingText(text = "MPD218を接続してください")
            } else {
                Text(
                    text = "接続完了",
                    fontSize = 32.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .width(200.dp)
                        .height(80.dp)
                ) {
                    Text("はじめる", fontSize = 24.sp)
                }
            }
        }
    }
}

@Composable
fun PulsingText(text: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Text(
        text = text,
        fontSize = 24.sp,
        modifier = Modifier.scale(scale)
    )
}

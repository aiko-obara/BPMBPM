package com.mpd218.training.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mpd218.training.model.PracticeMode

@Composable
fun ModeSelectScreen(
    onModeSelected: (PracticeMode) -> Unit,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = { onModeSelected(PracticeMode.TIMING) },
                modifier = Modifier
                    .width(300.dp)
                    .height(100.dp)
            ) {
                Text("タイミングれんしゅう", fontSize = 28.sp)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { onModeSelected(PracticeMode.MEMORY) },
                modifier = Modifier
                    .width(300.dp)
                    .height(100.dp)
            ) {
                Text("パッドおぼえる", fontSize = 28.sp)
            }
        }

        TextButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Text("← もどる", fontSize = 20.sp)
        }
    }
}

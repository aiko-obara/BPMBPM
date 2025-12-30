package com.mpd218.training

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mpd218.training.midi.MidiManager
import com.mpd218.training.model.AppScreen
import com.mpd218.training.model.PracticeResult
import com.mpd218.training.ui.*
import com.mpd218.training.viewmodel.PracticeViewModel

class MainActivity : ComponentActivity() {

    private lateinit var midiManager: MidiManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        midiManager = MidiManager(applicationContext)

        setContent {
            MPD218TrainingTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: PracticeViewModel = viewModel(
                        factory = PracticeViewModelFactory(midiManager)
                    )
                    AppNavigation(viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        midiManager.disconnect()
    }
}

@Composable
fun AppNavigation(viewModel: PracticeViewModel) {
    val state by viewModel.state.collectAsState()

    when (state.currentScreen) {
        AppScreen.Connection -> {
            ConnectionScreen(
                isConnected = state.isConnected,
                onStart = { viewModel.navigateToHome() },
                onScan = { viewModel.scanForDevice() }
            )
        }

        AppScreen.Home -> {
            HomeScreen(
                onStart = { viewModel.navigateToModeSelect() }
            )
        }

        AppScreen.ModeSelect -> {
            ModeSelectScreen(
                onModeSelected = { mode -> viewModel.selectMode(mode) },
                onBack = { viewModel.navigateBack() }
            )
        }

        AppScreen.Practice -> {
            PracticeScreen(state = state)
        }

        AppScreen.Result -> {
            val result = PracticeResult.fromPracticeState(state)
            ResultScreen(
                result = result,
                onContinue = { viewModel.continuePractice() },
                onBack = { viewModel.navigateBack() }
            )
        }
    }
}

@Composable
fun MPD218TrainingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        content = content
    )
}

package com.mpd218.training.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mpd218.training.midi.MidiManager

class PracticeViewModelFactory(
    private val midiManager: MidiManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PracticeViewModel::class.java)) {
            return PracticeViewModel(midiManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

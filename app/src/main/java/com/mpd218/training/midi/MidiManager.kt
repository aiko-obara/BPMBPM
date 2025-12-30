package com.mpd218.training.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager as AndroidMidiManager
import android.media.midi.MidiReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MPD218 MIDI Manager
 * Handles USB MIDI connection and input processing
 */
class MidiManager(private val context: Context) {

    private val androidMidiManager = context.getSystemService(Context.MIDI_SERVICE) as AndroidMidiManager

    private var midiDevice: MidiDevice? = null
    private var inputPort: MidiInputPort? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _padInput = MutableStateFlow<PadInput?>(null)
    val padInput: StateFlow<PadInput?> = _padInput.asStateFlow()

    // MPD218 Note number to pad index mapping (Bank A)
    // Pads 1-16 correspond to MIDI notes 36-51 (C1-Eb2)
    private val NOTE_TO_PAD = (36..51).mapIndexed { index, note -> note to index }.toMap()

    private val midiReceiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            try {
                if (count < 3) return

                val status = msg[offset].toInt() and 0xFF
                val noteNumber = msg[offset + 1].toInt() and 0xFF
                val velocity = msg[offset + 2].toInt() and 0xFF

                // Note On: 0x90-0x9F
                if (status in 0x90..0x9F && velocity > 0) {
                    NOTE_TO_PAD[noteNumber]?.let { padIndex ->
                        _padInput.value = PadInput(
                            padIndex = padIndex,
                            velocity = velocity,
                            timestamp = System.currentTimeMillis()
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore malformed MIDI messages
            }
        }
    }

    fun scanForMPD218() {
        val devices = androidMidiManager.devices
        for (deviceInfo in devices) {
            if (isMPD218(deviceInfo)) {
                connectToDevice(deviceInfo)
                return
            }
        }
        _isConnected.value = false
    }

    private fun isMPD218(deviceInfo: MidiDeviceInfo): Boolean {
        // MPD218 Vendor ID: 0x09e8 (2536), Product ID: 0x0025 (37)
        val properties = deviceInfo.properties
        val productName = properties.getString(MidiDeviceInfo.PROPERTY_NAME)
        return productName?.contains("MPD218", ignoreCase = true) == true ||
               productName?.contains("MPD", ignoreCase = true) == true
    }

    private fun connectToDevice(deviceInfo: MidiDeviceInfo) {
        androidMidiManager.openDevice(deviceInfo, { device ->
            try {
                midiDevice = device

                // Open input port (receive MIDI from device)
                val portInfo = device.info.ports.firstOrNull {
                    it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT
                }

                portInfo?.let {
                    inputPort = device.openInputPort(it.portNumber)
                    inputPort?.connect(midiReceiver)
                    _isConnected.value = true
                }
            } catch (e: Exception) {
                _isConnected.value = false
            }
        }, null)
    }

    fun disconnect() {
        try {
            inputPort?.disconnect(midiReceiver)
            inputPort?.close()
            midiDevice?.close()
        } catch (e: Exception) {
            // Ignore errors during cleanup
        } finally {
            inputPort = null
            midiDevice = null
            _isConnected.value = false
        }
    }

    fun clearPadInput() {
        _padInput.value = null
    }
}

/**
 * Represents a pad input event from MPD218
 */
data class PadInput(
    val padIndex: Int,      // 0-15
    val velocity: Int,      // 0-127
    val timestamp: Long     // System time in milliseconds
)

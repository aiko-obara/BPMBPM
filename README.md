# MPD218 Pad Training App

Android native application for training with Akai MPD218 MIDI pad controller.

## Features

- **USB MIDI Support**: Direct connection to MPD218 via USB
- **Two Training Modes**:
  - Timing Practice: Train rhythm and timing accuracy
  - Memory Practice: Learn and remember pad sequences
- **Real-time Feedback**: Instant visual feedback (○ △ ×) for each hit
- **Progressive Difficulty**: 5 levels with BPM-based difficulty
- **Offline First**: No internet connection required

## Requirements

- Android device with USB Host support (Android 6.0+)
- Akai MPD218 MIDI controller
- USB OTG cable (if needed)

## Architecture

- **Platform**: Android Native
- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Architecture**: MVVM
- **State Management**: StateFlow

## Building

```bash
./gradlew assembleDebug
```

## Installation

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Implementation Notes

See `claude.md` for detailed implementation specifications.

## TODO

- [ ] Add sound effect audio files to `res/raw/`
- [ ] Add app icon
- [ ] Add automated tests
- [ ] Add level progression logic

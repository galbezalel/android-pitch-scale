# Android Pitch Scaler

A system-wide Android app that captures background audio (from apps like Spotify, YouTube Music, etc.) and scales the pitch in real-time. This is perfect for singers, musicians, and dancers who need to practice to a song in a different key.

## Features
- **Real-Time Pitch Shifting:** Adjust the pitch by semitones up or down.
- **Background Audio Capture:** Uses Android's `AudioPlaybackCapture` API to intercept audio without needing root.
- **Pure Kotlin DSP Engine:** A custom-built, zero-JNI Phase Vocoder / Delay-Line DSP engine optimized for Android.

## Installation / Build Instructions
1. Clone the repository.
2. Open the project in **Android Studio**.
3. Build the project (`Build > Make Project`).
4. Connect your Android 10+ device and click the green **Run** button to install the debug APK.

## ⚠️ Important Limitation & The "Echo Hack"
Due to Android security sandboxing, third-party apps **cannot mute other apps**. When using the Pitch Scaler, the original unshifted audio will still play from the source app, causing an unavoidable "echo" effect.

**How to fix the echo (The Dual-Stream Hack):**
To overcome this, this app intentionally routes the pitch-shifted audio to the **Alarm** volume stream instead of the standard Media stream. 
1. Start playing music on your device.
2. Open Pitch Scaler and start shifting.
3. Use your phone's physical volume buttons to open the expanded volume menu.
4. **Turn the "Media" volume down to `1` (or `0` if your device allows capture at 0)** to silence the original song.
5. **Turn the "Alarm" volume all the way up** to hear the pitch-shifted audio clearly.

## Technical Details
- **Min SDK:** 29 (Android 10) - Required for `AudioPlaybackCapture`.
- **DSP Technique:** Two-Tap Phasor delay-line with Hanning windows and soft clipping.
- **Audio Routing:** `AudioRecord` (Media) -> `AudioTrack` (Alarm).

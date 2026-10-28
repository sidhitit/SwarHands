# Prompt: "Swar Hands" — Play Sa Re Ga Ma with Hand Gestures (Android, Kotlin)

## Role
You are a senior Android engineer experienced with CameraX, on-device ML (MediaPipe) and real-time audio. Build a complete, runnable Android Studio project in Kotlin.

## Goal
Build an Android app that uses the front camera to detect my hands, draws a live skeleton over each hand (21 joints and the bones between them), tracks my fingers every frame and plays Indian classical swar notes (Sa Re Ga Ma Pa Dha Ni Sa') when I make specific gestures.

## Hard constraints
1. **100% free.** No paid SDKs, no cloud APIs, no API keys, no subscriptions. Only open-source / free libraries.
2. **Fully on-device and offline** once installed. No network calls at runtime.
3. **No audio asset files required.** Synthesize the notes in code (PCM generated at startup and cached).
4. Kotlin only, Android Views (XML) UI, minSdk 24, targetSdk 34, portrait only.
5. Must build and run by opening the folder in Android Studio and pressing Run.

## Tech stack
- **Hand tracking:** MediaPipe Tasks Vision `HandLandmarker` (`com.google.mediapipe:tasks-vision`), `LIVE_STREAM` running mode, up to 2 hands, CPU delegate. Model `hand_landmarker.task` (free, from Google's public model bucket) downloaded automatically by a Gradle task into `src/main/assets` at build time.
- **Camera:** CameraX (`Preview` + `ImageAnalysis`), front camera, 4:3 aspect ratio for both use cases so the overlay aligns with the preview, analysis at ~640x480, `STRATEGY_KEEP_ONLY_LATEST`, `RGBA_8888` output.
- **Audio:** `SoundPool` for low-latency, polyphonic playback of synthesized WAV files written to `cacheDir`.

## Frame pipeline
1. `ImageProxy` → `Bitmap` → rotate by `rotationDegrees` → mirror horizontally (front camera) → `MPImage`.
2. `HandLandmarker.detectAsync(image, strictlyIncreasingTimestamp)`.
3. Result listener receives 21 normalized landmarks per hand plus handedness ("Left"/"Right"). Because the input is mirrored, the handedness label matches the user's real hand, and coordinates match the mirrored `PreviewView`.
4. Map normalized coordinates to view pixels using the same math as `PreviewView` `FILL_CENTER`: `scale = max(viewW/imgW, viewH/imgH)`, centred offset.
5. Run gesture logic, trigger sounds directly on the result thread (lowest latency), then hand an immutable UI frame to a custom overlay `View` and `postInvalidate()`.

## Landmark reference
0 wrist · 1–4 thumb (4 = tip) · 5–8 index (8 = tip) · 9–12 middle (12 = tip) · 13–16 ring (16 = tip) · 17–20 pinky (20 = tip).
Bones: 0-1,1-2,2-3,3-4, 0-5,5-6,6-7,7-8, 5-9,9-10,10-11,11-12, 9-13,13-14,14-15,15-16, 13-17,0-17,17-18,18-19,19-20.

## Musical model
Swar → semitones above Sa: Sa 0, Re 2, Ga 4, Ma 5, Pa 7, Dha 9, Ni 11, Sa' 12 (shuddh swar, Bilawal thaat).
`freq = SaHz × 2^(semitone/12)`. Default Sa = C4 (261.63 Hz); user can cycle Sa through all 12 keys (C to B) to match their scale.

Instruments, all synthesized:
- **Piano:** 8 harmonics, amplitude 1/h^1.4, per-harmonic exponential decay (higher harmonics decay faster), slight inharmonicity, 4 ms attack.
- **Harmonium:** 12 harmonics with two slightly detuned reeds (beating/chorus), 40 ms attack, sustained, 250 ms release.
- **Bansuri (flute):** sine plus weak 2nd and 3rd harmonics, delayed vibrato (~5.5 Hz), low-passed breath noise, soft attack and release.
Normalize every note to the same peak and add a 10 ms fade-out to avoid clicks. Cache WAVs by instrument and frequency so switching back is instant.

## Play modes

### Mode 1 — Pinch mode (default)
Touch the thumb tip to a fingertip to play. Two hands give 8 notes:

| Hand  | Index | Middle | Ring | Pinky |
|-------|-------|--------|------|-------|
| Left  | Sa    | Re     | Ga   | Ma    |
| Right | Pa    | Dha    | Ni   | Sa'   |

Detection algorithm:
- `handSize = distance(wrist 0, middle MCP 9)`, measured in pixel space (x×imgW, y×imgH, z×imgW×0.5), which makes it scale-invariant to distance from the camera.
- `ratio(finger) = distance(thumb tip 4, finger tip) / handSize`.
- **Hysteresis:** pinch starts when `ratio < 0.30`, ends when `ratio > 0.42`. This prevents flicker.
- Only the **closest** finger may start a pinch, and only when that hand has no active pinch (avoids double triggers from neighbouring fingers).
- Trigger the note on the **pinch onset** only (pinch → release → pinch again to replay). Minimum 90 ms between onsets per hand.
- Reset a hand's state when it leaves the frame.

### Mode 2 — Air keys
Eight coloured keys (Sa to Sa') drawn across the top band of the screen (9%–32% of height). Raise an index fingertip (landmark 8) into a key to play it; sliding across keys plays each one. Hysteresis: once inside a key, the zone is enlarged by 15% horizontally and 10% vertically before it counts as leaving. Works with either hand, independently per hand.

## UI / UX
- Full-screen camera preview with a transparent overlay drawing:
  - bones (left hand cyan, right hand orange), white joints, and an "L"/"R" tag at the wrist;
  - in Pinch mode, a coloured bubble on each fingertip showing its swar in Devanagari (सा रे ग म प ध नि सां), which grows and fills when pinched; the thumb tip is ringed;
  - in Air mode, the 8 keys (lit when touched) and a highlighted index fingertip;
  - a large fading flash of the last played note (Devanagari + Latin) in the centre.
- Bottom panel: a hint explaining the current mode's mapping, a Pinch / Air keys toggle, an instrument button (Piano → Harmonium → Bansuri) and a "Sa = C" pitch button.
- Top status pill: loading model, generating tones, ready, errors. It auto-hides when everything is ready.
- Keep the screen on. Request the CAMERA permission with the Activity Result API and show a clear message if it is denied.

## Architecture (files)
- `MainActivity` — permissions, CameraX binding, controls, wiring.
- `tracking/HandTracker` — MediaPipe wrapper (bitmap prep, monotonic timestamps, safe close).
- `gesture/Models` — `Swar`, `Finger`, `PlayMode`, `NoteMap`, `ViewTransform`, UI frame data classes.
- `gesture/PinchDetector` — normalized-distance pinch detection with hysteresis.
- `gesture/AirKeyboard` — key geometry and hit testing with hysteresis.
- `gesture/GestureEngine` — per-frame logic, sound triggering, builds the immutable UI frame.
- `audio/ToneSynth`, `audio/WavWriter`, `audio/SoundEngine` — synthesis, WAV encoding, SoundPool playback.
- `ui/OverlayView` — all drawing.

## Threading rules
- The analyzer and tracker create/close run on one single-thread executor, so `close()` can never race `detectAsync()`.
- Sound generation runs on its own executor. A generation counter cancels stale loads when the user switches instrument or pitch quickly.
- The overlay receives immutable frames via a `@Volatile` field; drawing happens only on the UI thread.

## Performance targets
- At least 20 FPS tracking on a mid-range phone, gesture-to-sound latency under 100 ms.
- No allocations of large buffers per frame beyond the bitmap; no blocking the UI thread.

## Edge cases
- Model file missing → show "model not found — build once with internet".
- Two hands with the same handedness label → keep separate state keys.
- Hand leaves the frame mid-pinch → clear its state, no stuck notes.
- Mode switch → reset gesture state.
- Rapid instrument/pitch switching → only the latest set of sounds is kept.

## Acceptance criteria
1. Skeleton visibly tracks both hands and stays aligned with the preview.
2. Each of the 8 pinch gestures plays the correct swar exactly once per pinch.
3. Air keys play when the index finger enters each key.
4. Instrument and Sa pitch changes take effect within about 2 seconds.
5. No crashes on rotation-locked portrait, background/foreground, or denying permission.

## Future extensions (not required now)
Komal/tivra swar via a gesture modifier, sustain-while-pinched with Oboe/AAudio, recording and playback of a sequence, tanpura drone, sargam practice mode that shows the next note to play.

# Swar Hands 🎶✋

Play **सा रे ग म प ध नि सां** with your fingers in front of the camera.
100% free: MediaPipe Hand Landmarker (on-device) + CameraX + tones synthesized in code. No API keys, works offline.

## Run
1. Open this folder in Android Studio (Koala or newer) → let Gradle sync.
2. Connect a phone and press Run. The first build needs internet: a Gradle task downloads
   `hand_landmarker.task` (~7.5 MB) into `app/src/main/assets/`.
   - If that download is blocked, get it manually from
     https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task
     and drop it into `app/src/main/assets/`.

## Play
**Pinch mode**: touch your thumb to a fingertip.

| Hand  | Index | Middle | Ring | Pinky |
|-------|-------|--------|------|-------|
| Left  | Sa    | Re     | Ga   | Ma    |
| Right | Pa    | Dha    | Ni   | Sa'   |

**Air keys**: raise your index finger into one of the 8 keys at the top of the screen.

Buttons: instrument (Piano / Harmonium / Bansuri) and Sa pitch (C … B).

## Games
The app now opens on a home hub with **Music** and **Games**. Add games in `games/GameCatalog.kt`.

**Stone Paper Scissors**: hold a fist ✊ for 3 s to start → 1-2-3 countdown → "Stone, Paper, Scissors!" →
throw your move. First to 2 wins (best of 3). Your opponent is a white cartoon glove drawn in code.
- Gesture rules: `games/rps/RpsGesture.kt` (`OPEN_RATIO` tunes how straight a finger must be)
- Timings: constants at the bottom of `games/rps/RpsGame.kt`

**Bullseye 🎯**: pick a weapon, then 3 stages × 5 shots (still target → moving target → wind). Rings score 10/8/6/4/2.
- **Slingshot (one hand)**: pinch thumb + index, pull down to stretch the band, open the pinch to shoot.
- **Bow (two hands)**: the bow slides along a fixed line; steer it left/right with one hand's two fingers ✌ and lean those fingers to tilt it up to ~50° each way.
  With the other hand pinch the red dot on the string, pull down, open the pinch to shoot. Arrows fly straight up.
- Pull distance = power. The green zone (80–95%) lands near the centre. Past 100% the weapon shakes
  and the shot gets random error; holding a full bow draw too long makes your arm tremble too.
- Gestures: `games/bullseye/AimTracker.kt` (`PINCH_ON` / `PINCH_OFF`, reach multipliers)
- Scoring, stages and power/shake tuning: constants in `games/bullseye/BullseyeGame.kt`
- Each shot has a 15 s clock; when it runs out the game shoots whatever you're holding.

**Gully Cricket 🏏**: one hand is your bat. Watch the bowler run in, swing as the ball reaches the crease.
- Timing picks the direction (early = leg side, late = off side), swing speed is power, an upward swing lofts it.
- Lofted and well timed = SIX (slow-mo replay). Mistimed lofts get caught; lofted side shots hit the houses:
  break a window and you're out (gully rule), hit the glowing golden window for +10.
- Ball types: medium, fast, slower ball (same run-up as medium), spin, bouncer, yorker. The run-up gives a hint.
- **Chase**: 5 levels, e.g. 12 off 6 balls up to 36 off 12. **Super Over**: bat until you lose 2 wickets, quicker every over.
- Swing detection: `games/cricket/BatTracker.kt` (`SWING_ON`, `POWER_FULL`)
- Timing window, latency, levels and shot outcomes: constants in `games/cricket/CricketGame.kt`

**Sign Says 🖐️**: a Simon-style memory game with hand signs. Watch the cards, then repeat them in order.
- Hold each sign until the ring fills to lock it in. Each sign plays its own sargam note.
- Every cleared round adds one sign; harder signs unlock later (round 3, 5, 7) and the cards speed up. 3 lives; a wrong sign
  or 7 s without one costs a life and replays the round.
- Signs: 1–5 fingers, fist, 👍, 👎, 🤘, 🤙, 👌, plus two-hand 6–10 (5 + n) and the 🫶 heart.
  Reading them: `games/signs/Sign.kt` (`SignClassifier`). Hands cut off by the screen edge are ignored.
- Shapes (○ □ △ ⚡ ★) are drawn in the air with one pointing finger; hold still to finish.
  A held, still ☝ is the "One" sign, a moving one draws. Recognizer: `games/signs/ShapeRecognizer.kt`
  ($1-style template matching, plus a corner count to tell squares from circles).
- Rounds, timings and scoring: constants in `games/signs/SignGame.kt`

## Tuning
- Pinch sensitivity: `PinchDetector(pinchOnRatio, pinchOffRatio)` in `gesture/PinchDetector.kt`.
- Key band position: `TOP` / `BOTTOM` in `gesture/AirKeyboard.kt`.
- Note mapping: `NoteMap` in `gesture/Models.kt`.

See `PROMPT.md` for the full spec this app was built from.

# v0.74 — Scope User Lock

This build changes TRAIN mode so scope tracking no longer depends on generic scene-wide motion detection finding the cue first.

## Main changes
- In-app Android SpeechRecognizer for TRAIN mode. The camera view remains visible while listening.
- Voice command `Track flag`, `Track foliage`, or `Track mirage` enters cue-selection mode.
- User taps the actual cue in the live image.
- Tap creates a persistent user-locked analysis region and bypasses the generic stabilizer/motion/semantic gate.
- New `ManualCueTracker` measures local optical flow directly inside the user-selected region.
- User-locked cues are not overwritten by ML Kit / Cloud Vision semantic labels.
- Observation progress is shown in the TRAIN progress bar.
- A completed user-locked observation is stored once in learning memory; voice corrections can then be stored against that live tracked observation.
- Existing speed output is retained, but the numeric bracket still remains gated by temporal evidence; otherwise the UI continues to show `SPEED LEARNING…`.
- Speed display remains <=2 mph wide and uses `12+ mph EST.` above the useful visual range.

## First test
1. Enter TRAIN.
2. Tap VOICE TRAIN and say `Track flag`.
3. The live camera must remain visible; no full-screen speech prompt should appear.
4. Tap the flag in the scope image.
5. Confirm the cyan ROI appears around the tapped flag.
6. Move/flap the flag and watch TRAIN progress increase.
7. When the app has a usable tracked cue, give corrections such as `Direction correct`, `Direction wrong`, `Speed higher`, or `Speed lower`.

The key validation for v0.74 is not automatic flag recognition. It is whether the app can follow and measure a user-identified moving cue through a spotting scope without first requiring generic motion detection to discover it.

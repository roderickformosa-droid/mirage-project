# v0.73 Voice Learning

Adds a user-supervised TRAIN mode for field calibration.

- TRAIN button toggles voice-learning mode.
- VOICE button becomes VOICE TRAIN while active.
- Supported commands include: track flag, track foliage, track mirage, direction correct/wrong, speed higher/lower, stable, changing, large flag, small flag, ignore.
- Each voice annotation is stored with the current analysis snapshot in `voice_training/voice_annotations.csv` in app external storage.
- A persistent learning-memory counter is stored in SharedPreferences.
- A visible learning bar shows progress toward the first 100 labelled field examples.
- This version stores supervised examples; it does not silently retrain the neural model on-device after each utterance. The dataset is intended to support later cue-specific calibration/model updates without destabilizing live behavior.

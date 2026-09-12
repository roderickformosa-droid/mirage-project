# Mirage Field Tester v0.61 — Night / Cue / Target Update

Built as an incremental update to v0.6, package unchanged: `com.mirage.app`.

## Field-test changes
- Mirage detection is now conservative: low-light/low-lux scenes, camera noise, and strong solid-object motion are rejected before the app can display mirage traces.
- Mirage must pass several consecutive frames of texture/activity/coherence checks before being shown.
- At low ambient light the app defaults to `NO MIRAGE DETECTED` rather than drawing speculative mirage.
- Secondary moving cues remain active when mirage is unavailable.
- Added on-device ML Kit image labeling for centre-target and strongest moving-cue semantic hints (tree, foliage, branch, flag, rope/line, smoke, fog/mist, door, sign, metal/steel-like target, etc. when the model has enough confidence).
- Fixed red crosshair at the exact centre of the screen. Centre-target label is displayed as `TARGET: ...` immediately beneath it.
- Moving cues no longer get a large circle. A pointer arrow identifies the exact moving region; a separate yellow arrow shows motion/wind direction.
- Main status and AZ / ANGLE / RANGE readouts are larger and moved into the central visible area.
- `SEARCHING — INSUFFICIENT SIGNAL` replaced by `NO MIRAGE DETECTED` when no mirage is confirmed.
- Battery display now explicitly says `BATTERY TEMP`.
- Proactive heat management: analysis throttles at 36°C and 37°C battery temperature and stops camera/analysis at 38°C until the battery cools to 36.5°C or below. This cannot guarantee a fixed battery temperature because ambient heat, charging, display brightness, case, and phone hardware also affect temperature.
- GPS button is now a real ON/OFF toggle.
- Pinch-to-zoom added while retaining + / - zoom buttons.
- Added push-to-talk VOICE zoom commands: “increase magnification” / “decrease magnification” (and zoom-in / zoom-out variants), preferring offline recognition when the phone supports it.
- New recordings are saved to the phone gallery under `DCIM/Camera` on modern Android.
- Bottom controls are now two fixed rows instead of a horizontal scrolling strip.

## Important limits for testing
- The semantic AI label is a general on-device classifier, not a purpose-trained long-range shooting model. Treat labels as hypotheses and report wrong labels so a future custom model can be trained.
- A monocular camera cannot infer true wind speed from object motion without distance/scale assumptions. Wind speed remains explicitly estimated.
- Auto range still depends on available geometry / GPS / pitch assumptions and intentionally returns `--` when the geometry is not trustworthy.
- Do not trust mirage direction until the drawn traces visually agree with what the shooter sees through the optic.

## Version
- versionCode: 7
- versionName: 0.61-night-field-fix

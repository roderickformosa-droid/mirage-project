# v0.69 — Adaptive Observer field test

Built on v0.68 Temporal Wind. This release incorporates the recovered field workflow.

## User workflow
- Auto-detects PHONE CAMERA vs SPOTTING SCOPE and always shows the active mode.
- Manual MODE button cycles AUTO → PHONE → SCOPE so the operator can correct a wrong detection.
- User-facing stages are SCANNING → DETECTED → ANALYSING → RESULT.
- Wind opinions publish no faster than once every 3 seconds while a rolling 10-second evidence window continues learning.
- Progress bar represents useful evidence quality/persistence/agreement, not a simple countdown.
- Feedback buttons are shown only while a current RESULT is presented.

## Cue strategy
- Starts with easy, readable wind-sensitive motion first: flags/banners/pennants/windsocks/fabric/laundry/sails, smoke/mist, foliage/grass/reeds, then heavier branches/palms/lines.
- Added semantic vocabulary for laundry, towels/sheets, sails, mast pennants/windsocks and wind/weather vanes.
- On-device ML Kit + persistent motion tracking remain primary; Google Cloud Vision remains a low-volume fallback for uncertain moving cue crops.
- Multiple persistent cues plus mirage are fused over time; repeated pieces of one moving object are not treated as independent votes.

## Spotting-scope mode
- Masks the corners outside the scope optical circle and filters cue centres outside that circle.
- Step 1 prompts the user to focus on the target/scene.
- Step 2 prompts approximately mid-range focus for mirage if mirage is not yet detected.
- Soft target focus is not treated as a failure during mirage analysis. AccurateShooter guidance notes that heat waves are easier to observe when the scope is focused about midway to the target.
- Automatic camera zoom sweeps remain suppressed once spotting-scope mode is active.

## Result UI
- Direction is a large arrow for fast reading.
- Speed is always shown as a broad estimated bracket when a RESULT is published (for example 2.0–5.0 m/s EST.), never as false single-value precision.
- STABLE pulses green; CHANGING pulses red; no colour/result is forced when evidence is insufficient.
- Mirage-only strength brackets remain deliberately broad and are marked estimates; this is not an anemometer.

## Preserved
- Thermal throttling and camera stop/resume protection.
- GPS toggle, recording to DCIM/Camera, pinch and voice zoom, Cloud Usage screen/counters, user validation logging, watermark.
- Automatic impact detection remains excluded. No ballistic solution/holdover/firing-correction feature is included.

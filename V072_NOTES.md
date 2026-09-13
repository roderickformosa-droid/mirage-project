# v0.72 – Cue Calibration

Field-calibration build focused on more conservative environmental wind observation.

## Changes
- Keeps the visual wind UI from v0.71.
- Direction remains the primary output: arrow shows where the observed flow is going; Left Wind / Right Wind wording remains.
- Wind speed is no longer derived directly from pixel speed. Motion is first placed into conservative cue-behaviour bands.
- Numeric wind display is withheld until a recognised cue or mirage has been temporally stable for at least ~3 seconds and the observer has accumulated enough learning.
- Displayed numeric ranges are never wider than 2 mph.
- Strong/saturated visual movement is displayed as `12+ mph EST.` rather than inventing precision above the useful visual range.
- Mirage strength mapping is deliberately reduced and conservative.
- New operator calibration feedback shown only with a result:
  - Direction? YES / NO
  - Wind speed? LOWER / OK / HIGHER
- Calibration feedback is logged with the algorithm snapshot and does NOT alter the live estimate.
- Existing cloud fallback, scope/camera modes, rolling observer, thermal protection, recording, GPS, zoom controls and RifletalksTechnologies watermark remain.

## Field-test priority
1. Confirm the app identifies/tracks a real flag or foliage before showing a speed.
2. Check arrow direction against observed flow.
3. Use Direction YES/NO and Speed LOWER/OK/HIGHER to build calibration data.
4. Treat `12+ mph` as a saturation bucket; do not try to visually distinguish higher speeds.

## Build
- versionCode 18
- versionName `0.72-cue-calibration`
- visible badge `v0.72 • CUE CALIBRATION`

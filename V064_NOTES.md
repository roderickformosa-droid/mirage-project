# Mirage Field Tester v0.64 — Cloud Usage Validation

This build extends v0.63 and keeps all v0.61–v0.63 field-test features.

## New in v0.64
- Cloud Vision fallback remains **LABEL_DETECTION only** and is used only when local recognition is uncertain.
- Hard app-side Cloud Vision limit raised to **30 requests/day per device**.
- Existing **900 requests/month per-device** ceiling retained.
- **Session request counter** added (resets when the app process is restarted).
- Bottom HUD now shows `CLOUD <session> SESSION • <today>/30 TODAY • <month>/900 MONTH`.
- New **CLOUD USAGE** panel/menu shows:
  - cloud configured/disabled state
  - session request count
  - daily request count and limit
  - monthly per-device request count and limit
  - most recent cloud label/confidence
  - local estimated device cost note
- **OPEN GOOGLE CLOUD USAGE** launches the Google Cloud Vision API metrics page for the configured test project in the normal browser.
- App explicitly warns that local counters are **per-device**, while Google Cloud Console is authoritative for project-wide usage and billing.

## Existing validation features retained
- `AGREE / NOT SURE / DISAGREE` operator validation buttons. Operator feedback is logged but never changes the live algorithm decision.
- `RifletalksTechnologies` watermark at bottom centre.
- Stable/changing wind readout with cue source, direction and broad estimated speed range.
- Conservative low-light/mirage gating and `NO MIRAGE DETECTED • NO RELIABLE WIND CUE` fallback.
- On-device ML Kit image labels plus local optical-flow / motion tracking.
- Cloud AI used only as a low-frequency fallback on cropped moving candidates.
- Fixed centre target crosshair and target label.
- Moving-cue pointer/arrow rather than oversized circles.
- Pinch zoom + +/- buttons + offline voice zoom commands.
- Battery temperature label and aggressive heat throttling/safety stop.
- GPS toggle and existing local range-assist logic.
- Recordings saved to `DCIM/Camera` on supported Android versions.

## Important limitation
The Google terrain/elevation ray-intersection range estimator discussed during planning is **not yet enabled in this build**. v0.64 retains the existing local GPS/pitch range assist. Terrain ranging needs Google Elevation API enablement and its own credential/quota configuration before it should be shipped.

## API key
GitHub Actions reads the repository secret named exactly:

`CLOUD_VISION_API_KEY`

The key is not committed into the source tree. Restrict it to Cloud Vision API in Google Cloud.

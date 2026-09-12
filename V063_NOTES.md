# Mirage Field Tester v0.63 — Cloud recognition fallback

Adds a deliberately low-volume Google Cloud Vision fallback for uncertain moving wind cues.

## Behaviour
- Local OpenCV motion analysis remains primary.
- On-device ML Kit remains the first semantic classifier.
- Cloud Vision is asked only when a moving cue exists AND local semantic confidence is below 72%.
- Sends only a 320px-wide JPEG crop around the moving candidate; never continuous video.
- Uses LABEL_DETECTION only (one Vision feature/unit per cloud request).
- Cloud-derived cue labels are visibly suffixed `[CLOUD]` during testing.

## Hard usage guards
- minimum 60 seconds between cloud requests
- maximum 20 requests/day
- maximum 900 requests/calendar month
- counters persist across app restarts in SharedPreferences
- quotas are incremented before the network call, so failed requests still consume the app-side allowance

These app-side caps are intentionally below 1,000/month. Still configure a Google Cloud budget/quota as a second independent guardrail.

## API key setup
The key is NOT stored in this ZIP.

For GitHub Actions, create a repository secret named:
`CLOUD_VISION_API_KEY`

The build reads the environment variable with the same name. The workflow included in this project exports that secret to the Gradle build.

Restrict the API key to the Cloud Vision API and, where practical, to the Android application/package/signing certificate used for the tester build.

If no key is supplied, v0.63 builds and runs normally with cloud recognition disabled.

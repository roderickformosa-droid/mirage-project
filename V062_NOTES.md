# Mirage Field Tester v0.62 — validation build

This build extends v0.61 for multi-operator field testing.

## Added
- Operator validation buttons: AGREE / NOT SURE / DISAGREE.
- Feedback is logged separately and never changes the algorithm output.
- Each vote stores the algorithm snapshot: mirage state/direction/stability, best visual wind cue, estimated speed range, cue confidence, target label, signal score and scene luminance.
- Stable wind readout now includes cue source, direction and estimated speed range in the green status bar.
- Changing wind readout includes cue source, direction and estimated speed range in the red status bar.
- Persistent `RifletalksTechnologies` watermark at the bottom centre of the live camera area.

## Important
- Wind speed remains an experimental visual estimate, not a calibrated anemometer reading.
- Operator feedback is validation data only. It does not retrain, bias, or override the decision during the test.
- Feedback CSV is stored in the app's external field_feedback folder for later export/analysis.

## Not active in this package yet
- Google terrain/elevation ranging is not enabled because it requires a Google Maps Platform API key and network configuration.
- Cloud object recognition is not enabled because it requires choosing/configuring a cloud vision service and credentials. v0.62 continues to use on-device ML Kit labels plus local motion analysis.

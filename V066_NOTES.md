# Mirage Field Tester v0.66 — Wind Fusion

This build focuses on the original environmental-observation goal and deliberately does **not** add automatic impact detection.

## Changes from v0.65
- Removed semantic target identification from the live pipeline. The app no longer tries to guess what the shooter is aiming at.
- Cloud/on-device semantic recognition is reserved for environmental wind cues.
- Added recognition mappings for grass, reeds, palm/palm fronds, foliage, twigs, branches, flags/fabric, rope/line, smoke, fog/mist and dust/debris.
- Added `WindFusionEngine`: combines mirage plus up to five visible motion cues into one conservative aggregate opinion.
- Different cue classes receive different response weights: smoke/fog/flags react readily; grass/foliage moderately; branches/palms/trees are treated as heavier evidence.
- Fusion checks direction agreement, cue stability, cue confidence, source diversity and persistence over time.
- Live result is now one of `STABLE CONDITION`, `CHANGING CONDITION`, or `NO RELIABLE WIND CONDITION`.
- Stable status still requires approximately 3 seconds of repeatable evidence.
- Main readout now shows fused direction, confidence, direction agreement and the cue types used in the decision.
- Broad wind-speed ranges remain explicitly estimates; direction/stability/confidence take priority.
- Feedback CSV now records the fused decision and its component evidence for later tester comparison.
- Fixed the v0.65 XML `NOT SURE` issue and the FrameAnalyzer timestamp typo in the packaged source.
- Visible build badge changed to `v0.66 • WIND FUSION`.
- Cloud Usage button now opens the project's saved Google Cloud Billing page.

## Intentionally not included
- Automatic bullet/impact detection or impact marking.
- Target-object classification.
- Ballistic corrections.

## Build verification
The environment used to prepare this source does not contain Android Gradle/SDK, so GitHub Actions remains the compile/build verification step. The standalone Kotlin wind-fusion logic was syntax-checked locally.

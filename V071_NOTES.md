# v0.71 – Visual Wind UI

Field-facing UI rebuild based on the approved mockup.

## Added
- Large visual direction arrow showing where the observed wind cue is travelling.
- Right Wind / Left Wind environmental labels.
- Compact top-view wind-flow visual.
- Wind speed shown in mph as a narrow 2 mph estimated bracket (for example 3–5 mph EST.).
- Confidence percentage removed from the field UI.
- Stable condition uses a green pulsing state; changing condition uses an amber/red state.
- Result card appears only when an estimate exists, leaving the camera image mostly unobstructed.
- Cue-source summary shows the visual evidence being used (flag, foliage, mirage, etc.).
- Mode control remains manually switchable between AUTO / PHONE / SPOTTING SCOPE.
- Existing 5–10 s evidence-learning workflow and ~3 s publication cadence retained.
- Existing thermal protection, cloud fallback, GPS, recording, zoom, validation feedback, and watermark retained.

## Important
- The UI reports environmental observations only: direction, stability, cue sources, and estimated speed bracket.
- It does not provide aiming, hold, or firing instructions.
- The "STABLE CONDITION" message means the visual wind evidence is temporally consistent, not that any shot outcome is guaranteed.

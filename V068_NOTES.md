# v0.68 — Temporal Wind / calmer field UI

This build changes both the user interface and the analysis architecture.

## Main behaviour changes
- Automatic optical-mode detection: PHONE CAMERA or SPOTTING SCOPE.
- Manual optical override button cycles AUTO → PHONE → SCOPE → AUTO.
- Spotting-scope mode analyses only the useful central optical field and ignores the dark eyepiece border.
- Spotting-scope mode does not reject mirage merely because the target plane is soft/out of focus; users may deliberately focus into the air mass.
- Digital auto-zoom sweeps are suppressed after spotting-scope mode is detected.
- Camera-analysis watchdog shows ANALYSIS INTERRUPTED / REACQUIRING and restarts the camera pipeline after a sustained stall.

## Wind-cue changes
- Environmental cues and mirage run concurrently.
- Strong visual cues are prioritised: flag/fabric, smoke/fog/mist, foliage/grass/reeds, then heavier branches/palms/lines.
- Heuristic FOLIAGE-LIKE classification was removed. Foliage now needs semantic recognition rather than motion geometry alone.
- Persistent cue tracker clusters repeated motion patches into one tracked cue and smooths label/direction over time.
- Elongated fabric-like regions estimate the likely free end from temporal motion energy, helping estimate the flag/fabric extension direction instead of using every flutter vector.
- Mirage continues independently and contributes to the aggregate opinion when detected.

## 10-second evidence / learning window
- Adds LEARNING CONDITION / INSUFFICIENT EVIDENCE percentage.
- Percentage measures useful evidence quality, persistence and agreement over a rolling 10-second window; it is not a countdown.
- Red bar = insufficient evidence; amber = useful evidence building; green = strong accumulated evidence.

## Calm field output
- Frame-by-frame cue arrows and labels are hidden in normal field mode.
- Main wind opinion publishes at most once every 3 seconds.
- Output shows general image-plane wind direction, broad estimated speed when justified, STABLE/CHANGING, confidence and sources.
- Stable condition pulses green; changing condition pulses red.
- If evidence disappears, a recent estimate may remain briefly as LAST ESTIMATE instead of flickering away.
- AGREE / NOT SURE / DISAGREE appears only while a current wind condition is being presented.
- A vote is logged against the published wind snapshot, not a newer hidden analysis frame.

## Not included
- Automatic bullet/impact detection remains excluded.
- No ballistic solution, holdover or firing-correction feature is included.

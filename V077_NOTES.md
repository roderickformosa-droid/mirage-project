# v0.77 AI Observer — target-first field workflow

Implemented in this build:
- Explicit PHONE / SPOTTING SCOPE mode; automatic scope detection is no longer the user workflow.
- Target-first acquisition flow: centre crosshair -> LOCK TARGET -> enter target distance -> START CUE SEARCH.
- AI/environmental analysis remains OFF until cue search starts.
- Target distance anchors the environmental observation corridor.
- Cleaner field UI: engineering/learning clutter hidden, +/- magnification buttons removed, compact bottom strip.
- Two-finger pinch is the only on-screen zoom gesture.
- PHONE mode pinch uses CameraX zoom.
- SPOTTING SCOPE mode pinch uses display-only digital enlargement (1x–4x) and never calls CameraX zoom, specifically to avoid physical-lens switching that would ruin phone/scope alignment.
- Existing automatic zoom search remains disabled in spotting-scope mode.
- Scope mode still asks the operator for optical magnification when the reasoning layer needs more real detail.

Important current limitation:
- The target lock in this iteration is a session observation-reference state, not yet a robust visual re-identification/tracking model. It establishes the workflow and range anchor without pretending target re-identification is solved.
- Persistent multi-turn conversational AI observer behaviour is the next architecture step; this build does not pretend the current independent reasoning calls are already a full conversation.
- Environmental observation only; no ballistic holds, firing corrections, trajectory advice, or shot-timing prompts.

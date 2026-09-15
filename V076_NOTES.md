# v0.76 — Reasoning-Gated Wind Scan

- Camera preview opens immediately for alignment; environmental analysis is idle until START WIND SCAN.
- START/STOP explicitly enables/disables reasoning and cloud/API work.
- Reasoning supervisor interval reduced to ~2.5 s during active development scans.
- Reason-first/render-second: wind graphics require credible reasoning-layer environmental cues as well as local temporal evidence.
- No credible cue => no wind graphic.
- Starts at 1x/wide view.
- Phone mode retains adaptive auto-zoom investigation.
- Spotting-scope mode blocks app-driven zoom sweeps and phone pinch/+/- zoom to avoid physical-camera switching; voice asks operator to adjust spotting-scope magnification when reasoning requests a closer look.
- Voice/TTS added for scan start and scope magnification guidance.
- Optional target range remains context for reasoning.
- Reasoner now returns needs_closer_look, focus_cue, and conservative NEAR/MID/FAR/UNKNOWN cue depth bands.
- Compact right-side evidence path shows observer, target range (if known), and reasoned cue depth bands; unknown depth stays approximate rather than inventing metres.
- v0.76 remains observational only: no ballistic holds, corrections, trajectory solutions, or firing commands.

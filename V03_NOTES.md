# Mirage Field Tester v0.3 — Visual Wind Assistant

This field-test build expands the prototype beyond mirage-only analysis.

## Added in v0.3
- AUTO ROI searches the stabilized scene for a stronger atmospheric signal; manual ROI remains available.
- AUTO ZOOM sweeps supported zoom ratios, scores the visual signal, and selects the best magnification. +/- gives immediate manual override.
- Secondary motion-cue detection scans the whole stabilized frame.
- Moving cues are circled in red and given an experimental visual label: FLAG/FABRIC-LIKE, FOLIAGE-LIKE, AIRBORNE OBJECT, or MOVING OBJECT.
- Motion direction is displayed as a clock direction (for example, 3 o'clock).
- Visual wind speed is shown as a broad estimated range in m/s with confidence. This is NOT a calibrated anemometer reading: monocular video lacks absolute distance/scale.
- A green STABLE indicator appears after roughly 3 seconds of consistent mirage or secondary-cue motion.
- Mirage clock direction is now shown in the main field display.
- CSV logging includes the new mirage and secondary-cue fields.

## Safety retained from v0.2
- Thermal throttling at MODERATE Android thermal status.
- Full processing/camera stop at SEVERE thermal status.
- Manual resume only after a thermal stop.
- 10-minute field-test session pause.
- Recording storage guards.

## Important field-test limitation
The object labels and wind-speed ranges are visual heuristics in this prototype, not a trained semantic AI model or a calibrated wind meter. Use the confidence value and compare against known conditions during testing. The purpose of v0.3 is to collect useful field evidence and identify what should be calibrated/trained next.

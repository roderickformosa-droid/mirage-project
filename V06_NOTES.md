# Mirage v0.6 Field Visualizer

Field-test UI update based on the September 12 review.

- Camera-first layout: approximately 90% live view, compact horizontal control strip.
- Debug graphs, ROI engineering controls, raw score/magnitude/residual readouts hidden from normal field view.
- True azimuth only; labels use ANGLE and RANGE rather than engineering abbreviations.
- GPS is user-triggered with a GPS button; location remains local to the app and assists geometric range estimation.
- Screen remains awake; auto/manual brightness remains available in the compact strip and thermal safety still caps brightness when needed.
- Battery temperature is shown in °C for field testing.
- Mirage is the primary atmospheric cue. When usable signal is present, the overlay draws only three sparse animated traces so the real mirage remains visible.
- Trace angle follows detected mirage direction. Trace wavelength is tightened for higher-frequency/denser activity and widened for slower activity.
- Overlay colour is selected for high contrast from scene brightness, with a dark outline to remain readable on mixed backgrounds.
- Last good mirage or secondary cue is held for about four seconds to prevent flicker/disappearing labels.
- Secondary motion cues are circled, labelled conservatively, and given an arrow in the observed movement direction plus an explicitly ESTIMATED wind-speed range.
- Green pulsing circle = stable evidence; red pulsing circle = changing evidence; neither when evidence is insufficient.
- Experimental centre-target helper runs when the stabilized view settles. This build has no bundled semantic neural-network model, so it deliberately uses conservative labels such as DOOR/SIGN-LIKE, TREE/FOLIAGE-LIKE, or UNCLASSIFIED TARGET rather than pretending to know an object it cannot identify reliably.
- No ballistic solver integration in v0.6.

## Important field-test limitations

Wind speed from visual cues remains an estimate. Monocular video cannot directly measure true air velocity without reliable scale/range and cue-specific aerodynamic behaviour. GPS + azimuth + inclination can assist range only when the geometry provides a valid surface intersection or a known range is supplied. Near-horizontal viewing angles can create very large range errors.

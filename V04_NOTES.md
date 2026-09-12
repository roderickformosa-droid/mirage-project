# Mirage Field Tester v0.4 — Range-Assisted Visual Wind

This prototype extends v0.3 with an optional distance layer so moving visual cues can be converted from pixels/sec into an approximate physical transverse speed.

## Added
- Phone heading and pitch from the rotation-vector sensor.
- Optional phone location permission. Location stays on-device; the app still has no INTERNET permission.
- AUTO RANGE mode:
  - GROUND: uses a 1.5 m camera-height assumption and downward viewing angle.
  - SEA: uses GPS altitude and downward viewing angle to intersect an approximate sea-level plane.
- KNOWN RANGE mode: user enters a trusted target/object distance in metres. This is preferred whenever available.
- Scope horizontal FOV input (default 2.0°). Required to turn image motion into angular motion through a spotting scope.
- Range-assisted physical object speed in m/s.
- Range-assisted wind estimate remains a broad range and confidence; it is not a calibrated anemometer.
- Overlay/logs now include distance source and object transverse speed.

## Important limitations
Geolocation + direction alone does not provide distance. AUTO RANGE only works when there is a meaningful downward/depression angle and a usable ground/sea-plane assumption. Near the horizon it intentionally reports no automatic range because the geometry becomes unstable.

Flags and foliage do not move at exactly the wind speed. Their physical motion is evidence about wind, not a direct wind measurement. Airborne debris can be closer to true advection but can also have inertia/gravity effects.

For the most credible field test, enter a known laser-ranged distance and the spotting scope's real horizontal FOV at the magnification being used.

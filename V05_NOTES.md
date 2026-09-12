# Mirage Field Tester v0.5 — Target HUD & Display Controls

Adds:
- Toggleable lower-right target-direction HUD.
- True azimuth (when location is available), magnetic heading, 16-point compass heading, inclination, and range.
- Screen kept awake while the app is active.
- Ambient-light automatic brightness with an in-app manual slider.
- Moving the brightness slider switches to manual mode immediately.
- Thermal-aware display brightness: at MODERATE or higher thermal state, app brightness is capped at 70% to reduce heat load.
- Existing thermal shutdown, storage guard, 10-minute safety pause, auto zoom, auto ROI, visual wind cues and range assistance retained.

Notes:
- The direction HUD treats the rear camera optical axis as the target line, so phone/scope alignment matters.
- True azimuth is derived from magnetic heading plus local geomagnetic declination when location is available. Without location, the true-azimuth value effectively matches magnetic heading.
- Automatic range remains an estimate unless known range or a stronger ranging source is supplied.

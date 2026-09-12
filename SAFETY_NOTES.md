# Mirage Field Tester — safety controls (v0.2-field-test-safe)

This prototype is designed for short field tests on Android. It does not request Internet access.

## Built-in safeguards

- **Thermal throttling:** when Android reports MODERATE thermal status, analysis is reduced from 7 Hz to 3 Hz.
- **Thermal stop:** at SEVERE thermal status or above, camera, analysis and any active recording are stopped.
- **No automatic thermal restart:** after a thermal stop, the app remains stopped. The RESUME button is enabled only after Android reports MODERATE or cooler, and the user must tap it manually.
- **10-minute session limit:** continuous analysis stops after 10 minutes and requires a manual RESUME.
- **Storage start guard:** recording will not start with less than 500 MB free.
- **Storage emergency stop:** while recording, free space is checked every 5 seconds; recording stops if free space falls below 250 MB.
- **No network permission:** the manifest does not request Internet access, so this build cannot upload camera/video data over the network.

## Field-test advice

Start with a short test. If the device becomes unusually hot, close the app and allow the phone to cool. Android and the phone manufacturer also maintain their own system-level thermal protections.

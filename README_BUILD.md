# Mirage Field Tester — Build & Install

**Important honesty note up front:** I don't have an Android SDK, Gradle,
or internet access in the environment I built this in, so I could not
actually compile this project myself or produce a ready-made APK file.
What you have is the complete, real source code for the app. Building it
takes about 15-20 minutes of mostly-waiting on your own computer, using
free official tools. If the build hits an error, copy/paste the exact
error text back to me and I'll fix the code — that's a normal part of
Android development, not a sign anything is fundamentally wrong.

## 1. Install Android Studio (one-time, ~15 min)

1. Download Android Studio from https://developer.android.com/studio
   (free, official, works on Windows/Mac/Linux).
2. Run the installer, accept the defaults. When it first opens, let it
   finish downloading the Android SDK components it prompts you about —
   this happens automatically the first time.

## 2. Open this project

1. Unzip the file I gave you (`mirage_android.zip`) somewhere you'll
   remember, e.g. your Desktop.
2. Open Android Studio → **File → Open** → select the unzipped
   `mirage_android` folder (the one containing `settings.gradle.kts`) →
   Open.
3. Android Studio will now "sync" the project — a progress bar at the
   bottom. This downloads CameraX, OpenCV, and other pieces automatically
   over the internet. **First sync can take 5-10 minutes** depending on
   your connection. Just wait for it to finish; don't close the window.

### If OpenCV via Maven fails

The `org.opencv:opencv:4.9.0` dependency in `app/build.gradle.kts` is the
modern, simplest way to include OpenCV — no separate download needed. If
Android Studio reports it can't find/resolve this dependency, tell me the
exact error and I'll switch the project to use a manually-downloaded
OpenCV Android SDK instead (a slightly more involved but well-documented
fallback).

## 3. Connect your phone

1. On your Android phone: **Settings → About phone** → tap "Build number"
   7 times (this unlocks Developer Options — a normal, harmless, official
   Android feature for exactly this purpose).
2. **Settings → Developer options** → turn on **USB debugging**.
3. Plug your phone into your computer with a USB cable.
4. Your phone will show a popup "Allow USB debugging?" — tap Allow.

## 4. Build and install

In Android Studio, with your phone connected: click the green **Run ▶**
button at the top (or **Run → Run 'app'**). Android Studio will:
- Compile the app,
- Install it directly onto your connected phone,
- Launch it automatically.

That's it — no Google account, no Play Store, no publishing, nothing sent
anywhere online. This produces a **debug APK** (the standard
unsigned-for-development build type), which is exactly right for field
testing.

### If you'd rather have a standalone APK file instead of "Run"

**Build → Build App Bundle(s) / APK(s) → Build APK(s)**. When it
finishes, a notification appears with a "locate" link to the file
(`app/build/outputs/apk/debug/app-debug.apk`). You can copy that file to
your phone (USB transfer, email to yourself, Google Drive, etc.) and tap
it on the phone to install — Android will ask permission to "install
from this source" the first time, which is a normal one-time toggle.

## 5. Grant permissions on first launch

The app will ask for **Camera** and **Microphone** (for the audio
narration track) permission on first open — allow both.

## 6. Known desktop ↔ Android differences (read before comparing numbers)

Everything was ported using the same formulas and parameter names as the
desktop Stage 1 tool wherever technically possible. Two specific spots
could not be made bit-for-bit identical, and are documented in code
comments as well as here so nothing was silently changed:

- **Stabilizer's RANSAC threshold**: the desktop tool explicitly sets
  `ransacReprojThreshold=3.0`. The Android build uses OpenCV's default
  RANSAC settings via a simpler function call. Effect should be minor;
  flag it if disturbance-flagging behaves very differently between the
  two on the same footage.
- **Frequency-domain FFT engine**: numpy's `rfft` (desktop) and OpenCV's
  `dft` (Android) are different implementations of the same underlying
  math. Bin frequencies and overall shape should match closely; exact
  decimal values are not guaranteed identical.
- **Grayscale conversion**: the desktop tool converts BGR frames to
  grayscale via OpenCV's standard luma formula. The Android build reads
  the camera's Y (luma) plane directly, which is conceptually the same
  signal but computed by the camera hardware/driver rather than by
  OpenCV's formula — should be very close, not guaranteed pixel-identical.
- **ROI on-screen alignment**: the box you drag on screen is mapped into
  the analysis image using a simple proportional scale (see
  `CoordinateMapper.kt`), which assumes the preview and analysis stream
  share the same aspect ratio. It has not been verified pixel-perfect on
  real hardware. Turn on DEBUG mode and check that the drawn vectors
  visually line up with what you expect inside the cyan ROI box — if they
  look offset, tell me and send a screenshot.

None of these change what a measurement *means* — they're implementation
details of how the same definition gets computed. If a field-test
comparison against the desktop tool shows a meaningful discrepancy beyond
these notes, that's useful data, not something to paper over.


## Optional cloud recognition (v0.63)
To enable the low-volume Google Cloud Vision fallback, add a GitHub repository secret named `CLOUD_VISION_API_KEY`. The workflow passes it to Gradle at build time. Without the secret, the APK still builds and runs using only local recognition. See `V063_NOTES.md` for quota safeguards.

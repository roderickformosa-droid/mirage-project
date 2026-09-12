# Timing & Synchronization

## What's used as the authoritative timestamp

Every analyzed frame's measurement row is timestamped using
`ImageProxy.imageInfo.timestamp` (`camera_frame_timestamp_ns` in the CSV)
— the timestamp CameraX attaches to that specific camera frame, not a
value read from the system clock after the fact. This is deliberate,
per the requirement that measurements be tied to the actual frame they
came from rather than to whenever the analysis code happened to get
around to running.

CameraX's video recording path (`VideoCapture`/`Recorder`) is built on
top of `MediaCodec`, which stamps encoded video frames using the same
underlying camera timestamp domain (`SurfaceTexture`/camera timestamps,
typically `CLOCK_MONOTONIC`-based on Android). That's *why* frame
timestamps and video presentation timestamps should be directly
comparable in principle — but this project has not yet run an experiment
that proves that alignment on your specific phone, which is exactly what
the field test is for.

## The cross-check clock

Each row also logs `wallclock_elapsed_realtime_ns`
(`SystemClock.elapsedRealtimeNanos()` captured at the moment analysis ran
on that frame). This is a second, independent clock reading. It will
lag slightly behind the true camera timestamp (there's always some delay
between "camera captured this frame" and "analysis code got around to
processing it"), and that's expected — the point isn't for the two
numbers to match, it's for the *elapsed time between rows* in each clock
to agree with each other over the course of a session. If they start
disagreeing (one clock says 60 seconds passed, the other says 61), that
indicates real clock drift worth knowing about, not analysis lag.

## How to actually verify sync (do this once, on real footage)

1. Record a short test clip where you do something visually and
   audibly unambiguous at a specific moment — e.g. clap once, or say
   "mark" while tapping the phone screen.
2. In the video, find the exact frame/time of that moment.
3. In `measurements.csv`, find the row whose `time_sec` (computed from
   `camera_frame_timestamp_ns` relative to the first logged frame) is
   closest to that same moment.
4. They should agree to within roughly one analysis interval (at 7 Hz,
   about 140ms). If they're off by much more than that consistently,
   tell me the specific numbers (video timestamp vs. CSV `time_sec`) and
   I'll dig into whether it's a fixed offset (easy to correct for) or
   genuine drift (needs a different approach).

## Known limitation

This has NOT been validated against real recorded output yet — it's the
designed approach, not a proven one. Treat the first field session partly
as a test of this sync claim itself, using the "clap test" above, not
just a test of the mirage measurements.

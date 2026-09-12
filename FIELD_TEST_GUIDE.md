# First Kowa Field Test

## Setup

1. Mount your phone to the Kowa via your digiscoping adapter as usual.
2. Open the app. You should see the live scope image full-screen.
3. If your phone has multiple rear cameras (main/ultra-wide/telephoto),
   use the dropdown at the bottom-left to pick the one actually aligned
   with the scope's eyepiece — usually the main lens, but check the
   preview to confirm you're seeing the scope's view and not the phone's
   own wide field of view.
4. Get the scope focused on mirage the way you normally would.
5. Drag the cyan box (drag the middle to move it, drag the bottom-right
   corner to resize it) onto the specific patch of mirage you want
   analyzed — same idea as picking the ROI in the desktop tool.
6. Once framing looks right and mirage is visible: tap **FOCUS: AUTO** to
   lock it (button will show "FOCUS: LOCKED"), and same for **EXP: AUTO**
   → locked. Watch the preview for a few seconds after locking — it
   should visibly stop hunting/refocusing or brightening/darkening. If it
   doesn't, that lock likely isn't supported on this phone for this
   camera — tell me and I'll note it as a real hardware finding.
7. Turn on **DEBUG** to see the vectors and confirm they're landing inside
   your ROI box where you expect.
8. Check the top-right indicator: it should say **MIRAGE DETECTED** once
   there's enough signal in your ROI. If it's stuck on **INSUFFICIENT
   SIGNAL** with clearly visible mirage in view, that's useful
   information — send me the session (see below) and I'll look at the
   threshold.

## The actual test (per your plan)

Don't fire a shot yet. Instead:

1. Tap **RECORD**.
2. Spend 5-10 minutes just watching the display while the mirage
   condition changes naturally, **narrating out loud** what you see —
   "holding... moving harder... flattening... holding again" — since the
   app records audio along with video specifically so this commentary
   becomes part of the permanent record.
3. Watch the four small rolling graphs on the right (activity, angle,
   magnitude, stabilization residual) while you narrate. The core
   question: **do the graphs visibly move at the same moments your eye
   and your own words say the condition changed?**
4. Tap **STOP** when you're done. A toast message will show you the
   folder it saved to.

Repeat for a few different conditions if you have the patience/time —
calm mirage, boiling mirage, a clear transition between the two, maybe
one clip with deliberately no mirage (early/overcast) as a negative
control, same as the desktop testing plan.

## What each session gives you

Every RECORD/STOP cycle creates one folder under the phone's
`Android/data/com.mirage.app/files/sessions/<timestamp>/` containing:
- `video.mp4` — the actual scope footage + your narration audio
- `measurements.csv` — every extractor's raw output, every analyzed
  frame, same column layout as the desktop tool (plus a few extra timing
  columns at the front)
- `session_info.txt` — device info and the timing-sync details needed to
  verify the video and CSV really line up (see below)

## Getting the files off your phone

Easiest: plug the phone into your computer via USB, then browse to
`Android/data/com.mirage.app/files/sessions/` in your file manager
and copy the session folder(s) to your computer. (On some phones you may
need to enable "file transfer" mode on the USB connection popup.)

## What to send back to me

For each test session, the whole session folder if possible (zip it if
that's easier) — specifically:
1. `measurements.csv` — so I can run it through the exact same
   `graphs.py` as the desktop tool and compare side-by-side.
2. `video.mp4` — so I can hear your narration against the actual footage
   and check the timing sync claim in `session_info.txt` for real (scrub
   to a moment you can identify, e.g. right when you say "moving harder,"
   and I'll check the nearest CSV row's timestamp lines up).
3. `session_info.txt` — device info, in case something behaves
   differently on your specific phone model.
4. Anything you noticed that isn't captured in the data — e.g. "the app
   felt laggy," "focus lock didn't actually seem to hold," "the ROI box
   drifted from where I put it" — these are exactly the kind of
   field-test findings this build exists to surface.

There's no red/green/orange judgment to make yet — just: **did the
numbers move when your trained eye said the mirage moved, and stay flat
when it didn't?** That's the whole question this version is built to
answer.

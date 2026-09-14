# v0.75 — Reasoning Supervisor

This build changes the architecture from “generic detector first” to a hybrid environmental-observation system.

- Adds an optional OpenAI vision/reasoning supervisor (`gpt-5.6-sol`) via the Responses API.
- The supervisor periodically inspects the whole visible scene and ranks useful environmental cues such as flags/fabric, smoke/dust, flexible foliage, grass/reeds, thin twig tips, and visible mirage.
- It explicitly rejects rigid/low-sensitivity objects when they are not useful evidence.
- It tolerates a soft target in spotting-scope mode because the scope may be focused into the air mass for mirage.
- Continuous motion measurement remains local and fast; the cloud model is not called for every frame.
- Adds a compact AI REASONING panel and manual target-range field.
- Keeps the existing scope user-lock / training path from v0.74.
- Keeps speed estimation gated behind usable evidence.
- The reasoning layer is observational only: no ballistic corrections, holds, trajectory solutions, or firing commands.

## GitHub secret required
Create repository secret `OPENAI_API_KEY`. The workflow injects it at build time.

For a production app, do not ship a long-lived OpenAI key inside the APK. Route requests through a controlled backend. BuildConfig injection is only for this prototype.

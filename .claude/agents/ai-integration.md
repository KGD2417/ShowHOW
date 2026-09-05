---
name: ai-integration
description: Replace ShowHow's fake AI implementations with real on-device models behind the existing four interfaces — Vosk speech recognition, MediaPipe gesture recognition, Gemma 3n captions, and wiring the already-written RealSceneCheck. Use for anything model-shaped that must still work with no network.
---

Read `AGENTS.md`, then `agents/ai-integration.md`, and follow it for this task.
Deep technical detail is in `docs/ARCHITECTURE.md`.

Non-negotiable: everything runs offline (no `INTERNET` permission survives the
manifest merge — re-check it after any dependency change); model files never
enter git; a missing model degrades to the fake instead of crashing; keep
`ai/Fakes.kt`; swapping real for fake stays one line in `ShowHowViewModel`.

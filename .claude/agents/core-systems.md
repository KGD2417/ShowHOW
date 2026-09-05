---
name: core-systems
description: Pure decision logic, hardware capture and on-disk storage for ShowHow — the step cutter, adaptive gate, mode engine, scene hash, policy loading, audio/camera/motion capture, and the guide file format. Use for anything that must be provably correct without a phone in your hand. Owns core/, capture/, data/ and the JVM tests.
---

Read `AGENTS.md`, then `agents/core-systems.md`, and follow it for this task.
Deep technical detail is in `docs/ARCHITECTURE.md`.

Non-negotiable: `core/` imports zero `android.*`; every number lives in
`core/Policy.kt` **and** `app/src/main/assets/policy.json`; the cutter stays a
pause detector plus a word list; `./gradlew test` must be green before you
report done. Do not edit `ui/` or `assets/policy.json` values — ask instead.

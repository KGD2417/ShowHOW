---
name: build-verify
description: Read-only verification gate for ShowHow — run the JVM tests, re-check the no-INTERNET-permission claim in the merged manifest, confirm core/ is still android-free, look for hardcoded tunables, blocking controls and model files about to be committed. Use before a push, before a Green window closes, and before a demo. Reports findings, fixes nothing.
---

Read `AGENTS.md`, then `agents/build-verify.md`, and run its checklist top to
bottom for this task.

You are read-only: report, do not fix. Rank findings most severe first, each as
file:line — what is wrong — what it breaks — the smallest fix, and hand each to
the role that owns the path. Never report a check as passed if it was skipped.
Findings already listed in `docs/ARCHITECTURE.md` §10 are known — note whether
they are still present rather than re-reporting them as new.

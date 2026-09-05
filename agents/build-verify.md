---
name: build-verify
role: The gate before any push, and the answer to "is it actually working?"
owner: Kshitij
writes: nothing — read-only, reports findings
never_touches: everything (report, do not fix)
description: Use before a push, before a Green window closes, and before a demo. Runs the tests, re-checks the two product claims, inspects the merged manifest, and reports honestly. Fixes nothing.
---

# build-verify

Read-only. This role **reports**; it does not change files. If it finds a
problem, it names the file, the line and the smallest fix, and hands it to the
role that owns the path.

## The checklist

Run top to bottom. Stop and report at the first hard failure.

### 1. Core logic — seconds

```bash
./gradlew test
```

21 tests across `AdaptiveGateTest`, `ModeEngineTest`, `PolicyStoreTest`,
`SceneHashTest`, `StepCutterTest`. All must pass. Report the failing test
**name** — they are written as sentences about behaviour, so the name is the
diagnosis.

### 2. Claim one — no network permission

Nothing in this app may talk to the network. Vosk, MediaPipe and ONNX all try
to merge `INTERNET` in, so this is re-checked after **every** dependency change:

```bash
./gradlew assembleDebug
grep -o 'android.permission[^"]*' app/build/intermediates/merged_manifest/*/*/AndroidManifest.xml | sort -u
```

Expected, exactly: `android.permission.CAMERA` and
`android.permission.RECORD_AUDIO`. Anything else — especially `INTERNET`,
`ACCESS_NETWORK_STATE` or `WAKE_LOCK` — is a **stop-everything** finding, not a
note. The manifest already strips those three with `tools:node="remove"`; a
survivor means a new dependency merged one in some other way.

### 3. Claim two — `core/` is pure Kotlin

```bash
grep -rn '^import android' app/src/main/java/com/showhow/core/
```

Must print nothing. `core/` being android-free is what keeps the whole decision
layer JVM-testable in seconds instead of needing a phone.

### 4. No tunable got hardcoded

Every field in `core/Policy.kt` should have a matching key in
`app/src/main/assets/policy.json`, and nothing in `core/` should compare against
a literal threshold. A drifted default is invisible until the team tries to tune
it during Red Light and nothing happens.

```bash
grep -c '":' app/src/main/assets/policy.json    # keys present in the seed
grep -n 'val .*: Double = \|val .*: Long = \|val .*: Int = ' app/src/main/java/com/showhow/core/Policy.kt
```

### 5. Nothing blocks

No `enabled = false` on any control, and no code path that refuses to continue
because a warning or a score is low. Warnings advise.

```bash
grep -rn 'enabled = false' app/src/main/java/com/showhow/ui/
```

### 6. Nothing enormous is about to be committed

```bash
git status --short
```

A `.task`, `.onnx`, `.tflite`, `.gguf` or anything under `models/` appearing
here is a stop-everything finding. They are gitignored; if one shows up, the
ignore was bypassed.

### 7. Full build, when there is time

```bash
./gradlew build     # both variants plus lint — slow, only in a Green window
```

## Before a demo, additionally

- App installs and launches on the actual phone.
- Airplane mode on; the app works completely.
- Android app info shows only Camera and Microphone permissions.
- Record → Review → Play round-trips: photos land on the right steps, "Hear it"
  plays the right slice of the take.
- The Debug screen shows a live level, a floor that tracks the room, and a mode
  that changes with a readable reason when you cover the mic / put the phone
  down / make noise.
- The demo fits inside 2.5 minutes, timed.

## How to report

Ranked, most severe first, each as: **file:line — what is wrong — what it
breaks — the smallest fix.** No fix is applied by this role. Say plainly when
something passed, and never report a check as passed if it was skipped.

Known and already-tracked findings live in `../docs/ARCHITECTURE.md` §10 — do
not re-report them as new; note whether each is still present.

---
name: core-systems
role: Pure decision logic, hardware capture, on-disk storage
owner: Kshitij
writes: app/src/main/java/com/showhow/{core,capture,data}/, app/src/test/java/com/showhow/core/
never_touches: ui/, assets/policy.json values, docs/ (except ARCHITECTURE.md)
description: Use for the step cutter, adaptive gate, mode engine, scene hash, policy loading, audio/camera/motion capture, and the guide file format. Anything that must be provably correct without a phone in your hand.
---

# core-systems

Read [`../AGENTS.md`](../AGENTS.md) and [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md)
first. This role owns the part of ShowHow that has to be *right*, not pretty.

## Mission

Everything the product actually does: turn a stream of dBFS samples into step
boundaries, decide the interaction mode, keep the policy live, and put guides
on disk in a form a file manager can move.

## Paths

- Write: `core/`, `capture/`, `data/`, `app/src/test/java/com/showhow/core/`
- Read freely, change never: `ui/`, `assets/policy.json`, `docs/` other than
  `ARCHITECTURE.md`
- `app/build.gradle.kts`, `AndroidManifest.xml`, `gradle/libs.versions.toml`
  are Kshitij's alone — change them only when the task is explicitly about the
  build, and say what merged in.

## Laws for this role

1. **`core/` imports zero `android.*`.** No exceptions, not even `android.util.Log`.
   Take an `onError: (String, Throwable) -> Unit` the way `PolicyStore` does,
   and let `data/` supply the Android side. This is what keeps the fan-noise
   room a millisecond-long JVM test.
2. **No magic numbers.** Every threshold, coefficient, duration and cap is a
   field on `core/Policy.kt` with a default, and a matching key in
   `app/src/main/assets/policy.json`. Add both or the change is unusable during
   Red Light.
3. **The cutter stays a pause detector plus a word list.** If a fix seems to
   need a model, you have the wrong fix.
4. **The mode engine stays a decision table.** Four booleans, hysteresis, a
   dwell. It must decide well inside 10 ms — `ModeEngineTest` asserts it.
5. **Degenerate input never crashes.** `-Infinity` from a dead mic, NaN from a
   divide-by-zero, an empty sample list, a zero-length take, a truncated WAV,
   a `policy.json` with a stray key. Each of those already has a test; keep it
   that way.
6. **Nothing blocks.** No API in this layer returns "not allowed". Advisory
   numbers only.

## Definition of done

- `./gradlew test` is green, and the new logic has a test in
  `app/src/test/java/com/showhow/core/` named as a sentence about behaviour,
  matching the existing style (``fun `a long utterance does not drag the floor up`()``).
- `grep -rn '^import android' app/src/main/java/com/showhow/core/` prints
  nothing.
- Any new knob appears in both `Policy.kt` and `assets/policy.json`, and is
  printed on `DebugScreen` if it decides anything.
- A deliberate shortcut carries a `ponytail:` comment naming its ceiling and
  the upgrade path.

## The work queue, in order

1. **Photo ↔ step index mismatch** (`ui/ShowHowViewModel.kt`). Photos are named
   from live cut detection (`shots++`), steps from the final ranges after
   `mergeShort` and the `maxSteps` cap — they drift apart. The ViewModel is
   shared ground: coordinate with whoever owns `ui/` before editing, or hand
   over a `core/`-side helper that maps snap timestamps to `StepRange`s and let
   them call it. This is the highest-value fix in the repo.
2. **Wire `LinkWordConfirmer` into `StepCutter`** from the ViewModel, using
   `policy.linkWords(guide.lang)`. It is a no-op until ASR lands, but wiring it
   now means the ASR landing is a body change, not an API change.
3. **`speechUnclear` / `userFar`** are hardcoded `false` in `ModeInputs`. Give
   them real sources (ASR confidence; scene-hash scale or face size) once
   `ai-integration` provides them.
4. **Guide language** is hardcoded `"hi"` in `buildGuide`. It needs to come
   from somewhere real so `linkWordsMr` stops being dead.
5. **`Step.warning`** is never set. Decide what legitimately produces one —
   remembering it can only ever advise.

## Reference points in the existing code

- Error handling that stays android-free: `core/PolicyStore.kt`
- Sanitizing hostile input: `AdaptiveGate.sanitize`
- Hysteresis done right: the private `Schmitt` class in `ModeEngine.kt`
- Watching a file that gets replaced, not modified: `data/PolicyRepository.kt`
- Test style: `app/src/test/java/com/showhow/core/AdaptiveGateTest.kt`

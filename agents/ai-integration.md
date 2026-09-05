---
name: ai-integration
role: Replace the fakes with real on-device models, behind the existing interfaces
owner: Kshitij
writes: app/src/main/java/com/showhow/ai/, the one AiStack line in ShowHowViewModel
never_touches: ui/ layout, assets/policy.json values, docs/
description: Use for Vosk speech recognition, MediaPipe gesture recognition, Gemma 3n captions, and wiring RealSceneCheck. Everything model-shaped, and everything that must still work with no network.
---

# ai-integration

Read [`../AGENTS.md`](../AGENTS.md) and [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md)
first. This role turns the canned answers into real ones **without changing a
single call site above `ai/`**.

## The contract that already exists

`ai/Ai.kt` defines four interfaces and one bundle:

```kotlin
interface Asr          { suspend fun transcribe(wav: File): List<Word> }
interface Captioner    { suspend fun caption(jpg: File): String }
interface GestureSource{ fun start(): Flow<Gesture> }
interface SceneCheck   { fun compare(live: Bitmap, saved: Bitmap): Float }
data class AiStack(val asr, val captioner, val gestures, val sceneCheck)
```

`ShowHowViewModel` holds `val ai: AiStack = fakeStack()`. **That line is the
entire integration surface.** If landing a real model needs a change anywhere
else, the design has slipped — stop and fix the interface instead.

## State today

| Interface | Implementation | Library declared | Status |
|---|---|---|---|
| `Asr` | `FakeAsr` — 9 canned Hindi words | `vosk-android` 0.3.75 | not imported |
| `Captioner` | `FakeCaptioner` — 4 canned strings | `mediapipe-tasks-genai` (Gemma 3n) | not imported |
| `GestureSource` | `FakeGestureSource` — `OPEN_PALM` every 2 s | `mediapipe-tasks-vision` | not imported, **and nobody collects the flow** |
| `SceneCheck` | `RealSceneCheck` — real, dHash + HSV | none needed | **written but never called** |

## Laws for this role

1. **Offline or it does not ship.** No model may fetch anything. The manifest
   has no `INTERNET` permission and strips it at merge; if a library needs the
   network to initialise, it is the wrong library.
2. **Models never enter git.** `.task`, `.onnx`, `.tflite`, `.gguf` and
   `**/models/` are gitignored. They live on the phone and in Drive, moved by
   Office Kit file transfer. If `git status` shows one, stop.
3. **A missing model file degrades, it does not crash.** Every real
   implementation falls back to its fake (or to an empty result) when the model
   is absent, and says so once in a log line. The demo must survive a phone
   that never got the file.
4. **Nothing here gets a veto.** `SceneCheck` returns a number. `Asr` returns
   words with confidences. The decision belongs to `core/`, and the answer to
   the user is always advice.
5. **Keep the fakes.** They are how the app stays demoable and how anyone
   without a 3 GB download can run the project. Never delete `Fakes.kt`.
6. **Everything model-shaped is expensive.** Nothing on the main thread; use
   `Dispatchers.Default`/`IO` and honour cancellation, the way
   `AudioRecorder.record` does.

## The work queue, in order

1. **`RealSceneCheck` is free — call it.** `ShowScreen` binds the camera with
   `analyzer = null`. Feed frames through an `ImageAnalysis.Analyzer`, compare
   against the current step's photo, and let the Player show "this doesn't look
   like the photo" as advice. Nothing new to download, and it demonstrates the
   whole on-device claim in one line of UI.
2. **Vosk ASR.** `transcribe(wav)` over the finished `take.wav` (already
   PCM16/16 kHz/mono, which is exactly what Vosk wants — that is why the
   recorder writes its own header). Return real `Word` timings. This unlocks
   `LinkWordConfirmer` (word-confirmed cuts), real step titles, and
   `speechUnclear` from confidence.
3. **MediaPipe gestures.** Recognise from the same `ImageAnalysis` stream as
   the scene check — one analyzer, not two. Then somebody has to actually
   collect the flow: HANDS mode does nothing until the Player consumes it.
4. **Gemma 3n captions.** ~3 GB behind a Hugging Face licence acceptance, so
   start the download before anything else. Lowest priority — `FakeCaptioner`
   is convincing on stage and this one is the most likely to be cut for time.

## Build notes that will bite

- R8 is off in release **on purpose**: it breaks MediaPipe, which stack-walks
  to load its native libraries. Do not re-enable it.
- All three ML libraries ship their own `libc++_shared.so`; the merger refuses
  to pick, hence `pickFirsts` in `app/build.gradle.kts`.
- `arm64-v8a` only. The demo fleet is arm64 and four ABIs would triple an APK
  that only ever travels over USB.
- Every one of these libraries tries to merge `INTERNET` into the manifest.
  After any dependency change, re-run the merged-manifest check in
  `AGENTS.md` §3.

## Definition of done

- `./gradlew test` green; `./gradlew assembleDebug` succeeds.
- The merged manifest still shows only `CAMERA` and `RECORD_AUDIO`.
- The app runs identically with the model files absent (falls back to fakes).
- Swapping real for fake is still one line in `ShowHowViewModel`.

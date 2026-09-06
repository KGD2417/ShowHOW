# ShowHow — architecture

Owner: Kshitij. The technical document the jury questions are answered from.
Start at [`../AGENTS.md`](../AGENTS.md) for constraints, ownership and commands;
this file is the detail underneath it.

Everything below describes code that exists today. Where something is a stub it
says so.

---

## 1. The product in one loop

**Show Mode** — the expert taps Start, does the job once, talks through it.
One continuous audio take is recorded. Silences in that take become step
boundaries. A photo is grabbed at each boundary. Stop, and ~a second later
there is a guide.

**Guide Mode** — the next person opens the guide and runs it with full hands.
Each step is a photo, a caption, and the slice of the original take where the
expert explained *that* step, in her own voice. The phone reads the room and
picks how to help; the user never chooses a mode.

## 2. Module map

```
app/src/main/java/com/showhow/
├── MainActivity.kt          permissions + setContent { ShowHowTheme { ShowHowHost() } }
├── core/                    PURE KOTLIN. No android.* import may ever appear here.
│   ├── Policy.kt            every tunable number, @Serializable
│   ├── PolicyStore.kt       load/reload, keeps last good value on bad JSON
│   ├── AdaptiveGate.kt      noise-floor tracker -> is this sample speech?
│   ├── StepCutter.kt        (samples, duration) -> List<StepRange>
│   ├── ModeEngine.kt        (motion, level, flags) -> Mode + reason string
│   └── SceneHash.kt         dHash + HSV histogram similarity, no model file
├── capture/                 the three hardware sources
│   ├── AudioRecorder.kt     PCM16/16k/mono WAV, hand-written header, dBFS flow
│   ├── CameraController.kt  Preview + ImageAnalysis + ImageCapture, bound together
│   └── MotionSource.kt      accelerometer magnitude variance over a window
├── ai/                      every model real; absent model means nothing, never a fake
│   ├── Ai.kt                Asr, Captioner, GestureSource, SceneCheck, AiStack
│   ├── DeviceAsr.kt         the system on-device recogniser, no word timings
│   ├── RealAsr.kt           VoskAsr + NoopAsr, wav -> Word[] with timings
│   ├── ObjectDetectSource.kt  MediaPipe detector; DetectorCaptioner names it
│   ├── MediaPipeGestureSource.kt  canned-gesture recognizer + NoGestures
│   ├── RealSceneCheck.kt    Bitmap adapter over core/SceneHash
│   ├── Narrator.kt          DeviceNarrator, offline TTS voices only
│   └── Coach.kt             on-device Gemma: step rewrite + learner Q&A
├── data/
│   ├── Guide.kt             Guide + Step, @Serializable
│   ├── GuideStore.kt        a guide is a folder on disk, no Room, no DataStore
│   └── PolicyRepository.kt  seeds the asset once, then FileObserver hot-reload
└── ui/                      Compose. Anushka's folder.
    ├── Screen.kt            sealed interface, five destinations
    ├── ShowHowHost.kt       the whole navigation graph is one `when`
    ├── ShowHowViewModel.kt  the only place the layers meet
    ├── ShowScreen / ReviewScreen / PlayerScreen / LibraryScreen / DebugScreen
    └── Theme.kt             stock Material 3 for now
```

`core/` being android-free is not tidiness. It is why "a room floored at -20 dB
by a ceiling fan" is a JVM test that runs in milliseconds instead of a trip to
a real kitchen with a real fan.

## 3. Recording data flow

```
mic
 └─ AudioRecorder.record(take.wav)          20 ms hops, 320 samples @ 16 kHz
     ├─ writes PCM16 to the WAV            (header rewritten on close, sizes
     └─ emits dbfs(buf) on `levels`         are only known then)
         │
         ▼
 ShowHowViewModel.onLevel(db)               50 calls a second
     ├─ gate.update(db)                     AdaptiveGate: floor + margin
     ├─ samples += Sample(tMs, db)          the authoritative log
     ├─ live boundary?  -> snap()           grab a photo for the next step
     ├─ _debug = _debug.copy(...)           feeds ShowScreen + DebugScreen
     └─ pushMode(db)                        ModeEngine.update(now, inputs)

 stopRecording()
     └─ buildGuide(id)
         ├─ StepCutter(policy).cut(samples, durationMs)   -> List<StepRange>
         ├─ ai.asr.transcribe(take.wav)                   -> List<Word>
         ├─ LinkWordConfirmer(policy words, spoken words)  -> vetoes cuts
         ├─ mapSnapsToSteps(snap times, ranges)            -> photo per step
         ├─ per range: Step(title, caption, startMs, endMs, photo, transcript,
         │              modeHint); caption comes from ai.captioner (canned)
         └─ GuideStore.save(guide)          guides/<id>/guide.json
```

Two separate cut passes exist on purpose: a cheap **live** one that only
decides *when to take a photo*, and the authoritative one that runs once at the
end over the whole sample log. They can disagree — see "Known defects".

## 4. The adaptive gate (the thing to be able to defend)

A fixed threshold cannot work. A ceiling fan floors a room around −20 dBFS; a
gate nailed at −38 dB reads that as continuous speech, finds no pause, and the
whole take comes back as one enormous step.

So the gate tracks the floor instead:

- `floorDb` starts at 0 and **falls fast** (`gateFallCoef` 0.25) toward any
  quieter sample — from 0 to a −20 dB fan in about half a second, which is why
  there is no priming special case.
- It **rises slowly** (`gateRiseCoef` 0.004), and is **frozen while speech is
  present**, so a long sentence cannot drag the floor up behind it. A room that
  genuinely gets louder is picked up in the next pause.
- `gateDb = (floorDb + speechMarginDb).coerceIn(gateMinDb, gateMaxDb)`.
- A silent mic reports −Infinity and a divide-by-zero reports NaN; both are
  sanitized to −120 before any arithmetic.

Tests: `AdaptiveGateTest` covers the fan-floored room, the clamp, the silent
mic, and "a long utterance does not drag the floor up".

## 5. The step cutter

1. Replay the sample log through a fresh `AdaptiveGate`.
2. Every silence run ≥ `pauseMs` that follows real speech yields a candidate
   cut **at the midpoint of the pause** — so the tail of one step and the run-up
   of the next both keep some air around them.
3. `CutConfirmer` gets a veto/second opinion. `LinkWordConfirmer` keeps a
   candidate only if `confirmMinLinkWords` linking words — "phir", "uske baad",
   "mag", "tyanantar", from `Policy` — were spoken within `confirmWindowMs`
   either side of it. Words near the cut are re-joined into one string first, so
   a two-token phrase like "uske baad" matches. With no recogniser, no word list
   or the knob at 0 it abstains and every cut passes: vetoing everything would
   hand back one enormous step, the exact failure the cutter prevents.
4. `mergeShort` drops the boundary after any segment shorter than
   `minUtteranceMs`, so it joins the next one. A short *tail* has no next, so
   the last boundary is dropped instead.
5. Hard cap at `maxSteps`; everything past the cap lands in the final step
   rather than being thrown away — the ranges must still tile the take.

Tests: merge-forward, the cap, exact tiling, a silence-only take, and the
confirmer pass-through.

## 6. The mode engine

Four modes, first match wins:

| Order | Mode | Condition | Meaning |
|---|---|---|---|
| 1 | EASY | user setting | overrides everything, no guessing |
| 2 | HANDS | room is loud, or speech was unclear | gesture / big-target interaction |
| 3 | TALK | phone is flat on a counter, or user is far | voice + across-the-room type |
| 4 | TAP | held, quiet, close | ordinary touch |

Two Schmitt triggers (`inHand` on accel variance, `roomLoud` on dBFS) give
enter ≠ exit thresholds so a value sitting on the line cannot flip. On top of
that a **dwell**: a candidate must survive `dwellMs` before it is committed.
Without the dwell a borderline sample repaints the screen twice a second, and
that flicker is exactly the problem the product exists to solve.

Every commit carries a human-readable `reason` string
(`"HANDS <- room is loud (-24.3 dBFS)"`). The Debug screen shows it. It is also
the honest-limits answer for the jury: the phone says *why*, it does not guess.

`ModeEngineTest` asserts hysteresis, the dwell, table precedence, that every
switch carries a reason, and that a decision lands well inside 10 ms.

## 7. Policy hot-reload

```
assets/policy.json ──seed once──▶ filesDir/policy.json ──▶ PolicyStore ──▶ StateFlow<Policy>
                                        ▲                                        │
                        FileObserver on filesDir                                 ▼
                        (CLOSE_WRITE | MOVED_TO | MODIFY)          ShowHowViewModel rebuilds
                        filtered to "policy.json"                  AdaptiveGate + ModeEngine
```

- The asset is a **seed, once**. After that `filesDir` is the only source of
  truth — reading the asset again would make an override impossible.
- The observer watches the **directory**, not the file: editors and `adb`
  replace rather than modify, and a watch on an inode dies with the inode.
- `ignoreUnknownKeys = true` — a hand-edited file with a stray key is not a
  crash.
- Bad JSON: logged, `lastError` set, **previous good values kept**. The Debug
  screen prints the error.

## 8. Storage format

```
filesDir/guides/<id>/guide.json     Guide { id, title, lang, createdAt, take, steps[] }
filesDir/guides/<id>/take.wav       the single narration take
filesDir/guides/<id>/snap0.jpg …    named by SNAP order, not step order; which
                                    step owns which snap is decided by time in
                                    core.mapSnapsToSteps and stored in Step.photo
filesDir/policy.json                the live policy
```

Deliberately plain file IO. A guide you can drag from one phone to another with
a file manager is the whole sharing story — no server, no export format.

## 9. UI

Navigation is a sealed interface and a `when`. Navigation-Compose buys nothing
for five screens and costs an argument about routes at 2 am.

| Screen | State today | Priority |
|---|---|---|
| `PlayerScreen` | photo + caption + "Hear it" (ExoPlayer clipping the take between `startMs`/`endMs`) + Back/Next buttons | 1 — needs four visual states and a mode reason bar; this is what the jury watches |
| `ShowScreen` | viewfinder, live level/gate readout, Start/Stop | 2 — needs a real mic meter and a step timeline building up live |
| `ReviewScreen` | read-only list of what the cutter produced | 3 — the pitch claims a person fixes the cuts in 20 s, so it needs join / split / re-record |
| `LibraryScreen` | list of guides + easy-mode switch | 4 — least important |
| `DebugScreen` | **done.** Every number that decides anything, plus the last mode reason and the live policy | — |

`ShowHowViewModel` is the only place the layers meet: it owns the recorder, the
motion flow, the gate, the engine, the guide store and the policy repo, and
exposes one `DebugState` so the debug screen repaints atomically.

## 10. Known defects and gaps

Ranked. Fix from the top. Everything here was checked against the code.

1. **No coach model exists on any phone.** `ai/Coach` is written, compiled and
   covered by tests against canned replies, and has never been loaded. Its load
   time, inference latency and memory use are unmeasured, and it will contend
   for RAM with Vosk and two MediaPipe graphs. Until a `.task` file is pushed,
   every coach path is a correct no-op and the app behaves as it did before the
   coach existed.
2. **`userFar` is still hardcoded `false`.** It needs a face height in pixels;
   `ShowHowViewModel.feedFaceSize` and `Policy.userFarFaceHeightPx` are in place
   waiting for a MediaPipe face detector. The suggested brightness proxy is not
   a distance measurement, and §2's fourth constraint says this app does not
   guess at what it cannot honestly measure.
3. **The detector is generic COCO.** It knows "laptop", "keyboard", "person",
   and has no label for a screw, a RAM module, an SSD or a screwdriver. This is
   kept explicit everywhere it matters: detector output is a *count* when
   picking frames, tool names in a learner's context come from what the expert
   said rather than from the detector, and a `VISUAL_FACT` answer is capped by
   what was actually returned. Swapping in a fine-tuned `.tflite` is a file
   push; nothing needs rebuilding.
4. **No swipe gestures.** A swipe is motion over time, not a pose, so the canned
   classifier cannot emit one. Palm and fist already cover ±1 step.
5. `speechUnclear` is computed from the *previous* take's mean word confidence,
   because Vosk runs once over the finished WAV rather than live. It is the
   right signal on the wrong clock; a streaming recognizer would fix it.
6. `GuideStore.delete` has no UI.
7. `com.google.android.datatransport` is pulled in transitively by MediaPipe and
   registers a broadcast receiver and a JobService in the merged manifest. It is
   inert -- the app has no `INTERNET` permission, so any upload attempt would
   throw -- but the offline guarantee rests on the stripped permission rather
   than on the library not being in the APK.

**Fixed, listed here so nobody re-reports them:** the photo-indexing defect
(photos are paired to steps by time in `core/mapSnapsToSteps`, then quality-
filtered by `core/pickFrames`, both held by tests); the fakes (`Fakes.kt` is
deleted, every model is real); `RealSceneCheck` being unwired; `LinkWordConfirmer`
being a no-op; the guide language being hardcoded `"hi"`; `Guide.title` being
uneditable; `commit()` overwriting the coach's step titles; `Step.warning` never
being written; and guide building running on the main thread.

## 11. Dependencies, and the model files that are not in git

`vosk-android` (ASR), `mediapipe-tasks-vision` (hand signs, object detection)
and `mediapipe-tasks-genai` (the coach) are the three ML dependencies, and all
three are used. `onnxruntime-android` was declared and never imported; removing
it took the debug APK from 122 MB to 91 MB.

The models themselves are gitignored and belong on the phone, not in the repo.
Every path is under `filesDir`, which is `/data/data/com.showhow/files`:

```
models/vosk-en/                 unzipped Vosk model, conf/model.conf must exist
models/vosk-hi/                 one directory per language, never a shared one
models/vosk-mr/                 a Vosk model speaks exactly one language
models/gesture_recognizer.task  MediaPipe canned-gesture model
models/object_detector.tflite   fine-tuned tool detector, see 12
models/object_detector_coco.tflite  stock COCO, runs beside it, see 12
models/coach.task               Gemma, for the step rewrite and the Q&A
```

Install, from a machine with the files unzipped in `./models/`:

```bash
adb push models /data/local/tmp/models
adb shell run-as com.showhow sh -c 'cp -r /data/local/tmp/models files/'
adb shell run-as com.showhow ls files/models        # verify before the demo
```

`run-as` is required: `filesDir` is not world-writable, so a plain
`adb push` straight to it silently fails on a non-rooted phone.

Every model is optional at runtime and nothing is ever replaced by a fake:

| Missing | What happens |
|---|---|
| `vosk-<lang>/` | that language drops out of the picker; `NoopAsr` if all three are gone, so empty transcripts and the pause detector standing alone |
| `gesture_recognizer.task` | `Gesture.NONE` forever, the Player's buttons still work |
| `object_detector.tflite` | no tool boxes; COCO still names the room |
| `object_detector_coco.tflite` | no laptop, keyboard, mouse or person; the tools still box |
| both detectors | no boxes over the viewfinder, empty captions, transcript carries the step |
| `coach.task` | steps stay in the expert's own words, the ask sheet falls back to its token match over the transcripts |

Swapping a detector is a file push, not a build — drop a different
MediaPipe-compatible `.tflite` in as `object_detector.tflite` and retune
`detectMinScore` in `policy.json`. That is the whole upgrade path during Red
Light.

R8 is off in release on purpose: it breaks MediaPipe, which stack-walks to load
its native libraries. All three ML libraries ship their own
`libc++_shared.so`, hence the `pickFirsts` in `packaging`.

## 12. The fine-tuned detector

The object detector shipped as generic COCO -- eighty everyday classes, none of
them a tool. On a bench it drew `laptop 0.76` around the laptop, `person 0.77`
around the hand holding a screwdriver, and nothing at all around the
screwdriver, because there is no such class and no configuration adds one.

`tools/` holds the three scripts that replaced it. They need a free Roboflow
key in `~/.roboflow_key`, WSL or Linux, and about half an hour:

```bash
python3 tools/fetch_screw_datasets.py    # three public COCO exports
python3 tools/merge_screw_datasets.py    # -> one class list, ~875 train images
python3 tools/train_detector.py          # -> ~/sd_out/screwdriver.tflite
```

Model Maker rather than YOLO for one reason: it writes TFLite Model Metadata,
and MediaPipe's ObjectDetector refuses a model without it. The export drops
straight over `files/models/object_detector.tflite`, and `componentAliases` in
policy.json is what `ai/ComponentLocator` reads to decide what it may be asked
to point at.

Measured on the merged dataset, 30 epochs, one laptop RTX 3060:

| metric | value | what it means |
|---|---|---|
| AP@0.50 | 0.635 | does it box the right thing |
| AP large | 0.668 | a screwdriver held up to the camera |
| AP small | 0.157 | a screw head in a wide shot -- weak, get closer |
| load | 18 ms, NPU | on the Snapdragon 8 Elite Gen 5 |

Classes: `screwdriver`, `philips_screw`, `pozidriv_screw`, `torx_screw`,
`hex_screw`, `square_screw`. `square_screw` had 23 training examples and should
be treated as untrained.

RAM, SSD, heatsink and battery are **still empty** in `componentAliases`. No
dataset covered them, so asking still answers "this detector has no label for
that" rather than borrowing a box from whatever happened to be nearby.

### Both models run, per frame

It did not replace COCO in the end -- it runs beside it. Held over a real bench
at `detectMinScore` 0.55 the fine-tuned model drew **nothing**; dropped to 0.02
it drew four boxes, one of them `philips` over a cartoon sticker. It is a small
model and a wide desk shot is exactly the case its AP small of 0.157 predicts it
will miss. Meanwhile a laptop repair guide with no `laptop` in it is not much of
a guide, and COCO was already sitting on the phone doing that job well.

So `ObjectDetectSource` takes a list of models and concatenates their boxes:

| model | answers for | floor |
|---|---|---|
| `object_detector.tflite` | `screwdriver` and the five screw heads | `detectMinScore` 0.30 |
| `object_detector_coco.tflite` | laptop, keyboard, mouse, person, tv | `detectMinScoreCoco` 0.50 |

Two floors because the models are not equally sure of themselves, and one
allowlist because they overlap: the fine-tuned `labels.txt` also carries
laptop, person, hand, keyboard and mouse, picked up along with the tool
datasets and backed by a handful of images each. COCO has tens of thousands per
class and wins those outright, so `TOOL_LABELS` in `ShowHowViewModel` holds the
fine-tuned model to the six classes it was actually trained for. Without it one
laptop gets two boxes and the weaker one sometimes wins.

Cost is a second inference per frame -- about 36 ms of NPU rather than 18, which
still fits inside the analyzer's 100 ms interval. Either file may be absent and
the other carries on; both absent is the empty-list case above.

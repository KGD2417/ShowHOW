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
├── ai/                      four interfaces; three implementations are fakes
│   ├── Ai.kt                Asr, Captioner, GestureSource, SceneCheck, AiStack
│   ├── Fakes.kt             canned answers, demoable with zero models
│   └── RealSceneCheck.kt    Bitmap adapter over core/SceneHash  (UNUSED today)
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
         ├─ per range: Step(title, caption, startMs, endMs, photo)
         │              caption comes from ai.captioner (fake today)
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
3. `CutConfirmer` gets a veto/second opinion. `PassThroughConfirmer` is used
   today; `LinkWordConfirmer` exists but is a no-op until ASR lands, at which
   point it confirms a boundary where the expert actually said "phir",
   "uske baad", "mag", "tyanantar" (word lists live in `Policy`).
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
filesDir/guides/<id>/s1.jpg …       one photo per step, ONE-BASED on disk (s1 = index 0)
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

Ranked. Fix from the top.

1. **Photo ↔ step index mismatch.** `snap()` names files by `shots++`, driven
   by *live* boundary detection in `onLevel`. `buildGuide` assigns
   `photoFile(id, r.index)` from the *final* ranges, after `mergeShort` and the
   `maxSteps` cap have removed boundaries. Live cuts ≥ final steps, so photos
   drift onto the wrong steps. Fix: record the timestamp of each snap, then map
   snaps to ranges by time in `buildGuide`.
   (`ui/ShowHowViewModel.kt` — `snap`, `onLevel`, `buildGuide`.)
2. **ASR is fake.** `FakeAsr` returns nine canned Hindi words. Vosk is declared,
   never imported. Landing it also unlocks `LinkWordConfirmer` and
   `speechUnclear`.
3. **`LinkWordConfirmer` is a no-op and is not even passed to `StepCutter`.**
   Wiring it is a body change, not an API change — the words are already in
   `Policy`.
4. **Gestures are fake and nobody collects the flow.** `FakeGestureSource`
   emits `OPEN_PALM` every two seconds into the void. HANDS mode therefore does
   nothing.
5. **`RealSceneCheck` is never called.** `ShowScreen` binds the camera with
   `analyzer = null`. The Player should compare the live frame to the step photo
   and show an advisory line — advisory, never a block.
6. **Mode output is invisible.** Only `DebugScreen` reads `debug.mode`.
   `PlayerScreen` needs the four visual states.
7. `speechUnclear` and `userFar` hardcoded `false`; guide `lang` hardcoded
   `"hi"` so `linkWordsMr` is dead; `Step.warning` is never set;
   `GuideStore.delete` has no UI; titles are `"Step N"` / `"Guide N"`.
8. Captions come from `FakeCaptioner`'s four canned strings, cycled.

## 11. Dependencies declared but not yet used

`mediapipe-tasks-vision` (gestures), `mediapipe-tasks-genai` (Gemma 3n
captions), `onnxruntime-android`, `vosk-android` (ASR). They are in the build
tonight so the APK size and the manifest merge are known quantities before
anyone is tired — that is also why the debug APK is 121 MB.

R8 is off in release on purpose: it breaks MediaPipe, which stack-walks to load
its native libraries. All three ML libraries ship their own
`libc++_shared.so`, hence the `pickFirsts` in `packaging`.

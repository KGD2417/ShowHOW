# AGENTS.md — ShowHow

Read this before touching anything. It is the entry point for **any** coding
agent (Claude Code, Cursor, Codex, Copilot, Aider, Continue, Zed) or human.
Tool-specific files (`CLAUDE.md`, `.github/copilot-instructions.md`) only point
back here.

- Deep technical map: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- Team, git and schedule rules: [`docs/WORKFLOW.md`](docs/WORKFLOW.md)
- Role agents (pick one before you start): [`agents/README.md`](agents/README.md)

---

## 1. What ShowHow is

An offline Android app. An expert does a job once and narrates it in Hindi or
English. The phone turns that single take into a step-by-step guide — one photo
and one audio slice per step. Anyone else then runs the guide hands-free.
Everything runs on the device; nothing leaves the phone.

Marathi is not offered for recording: Vosk ships no Marathi model, and a button
that records a take and returns nothing is the failure this app refuses. The
Marathi linking words, voice and labels are all still there for guides already
recorded in it. See `VoskAsr.LANGUAGES`.

Built for **iQOO City Battles 2026** (30-hour hackathon, three-person team).

## 2. The four hard constraints

These are product claims made to a jury. Breaking one is worse than shipping
less. Never "temporarily" relax one.

1. **No `INTERNET` permission.** The manifest declares only `CAMERA` and
   `RECORD_AUDIO`, and strips `INTERNET`, `ACCESS_NETWORK_STATE` and
   `WAKE_LOCK` with `tools:node="remove"` in case a dependency merges them in.
   The demo proves this in the Android app-info screen in four seconds.
2. **The step cutter is a pause detector plus a word list.** No video model, no
   ML anywhere in that path. It must run on a five-year-old phone.
3. **The mode engine contains no AI.** Plain rules over four booleans, decides
   in well under 10 ms.
4. **Warnings advise, they never block.** There is no disabled button anywhere
   in the app. Anything the phone cannot honestly measure (gloves, driving,
   age, whether the work is correct) is a *setting*, never a guess.

## 3. Invariants an agent must not break

| Invariant | How to check it |
|---|---|
| No network permission survives the merge | `grep -o 'android.permission[^"]*' app/build/intermediates/merged_manifest/*/*/AndroidManifest.xml \| sort -u` |
| `core/` is pure Kotlin, zero `android.*` | `grep -rn '^import android' app/src/main/java/com/showhow/core/` must print nothing |
| Every tunable number lives in `Policy` | new knobs go in `core/Policy.kt` **and** `app/src/main/assets/policy.json` |
| A malformed `policy.json` never crashes and never resets tuning | `PolicyStoreTest` |
| Step ranges tile the take exactly — no gaps, no overlaps | `StepCutterTest` |
| Core logic stays JVM-testable in seconds | `./gradlew test` |

## 4. Stack and commands

Kotlin 2.4.10 · AGP 9.4.0 · Compose (BOM 2026.08.00, Material 3) · CameraX
1.6.2 · media3 1.11.0 · kotlinx-serialization + coroutines. `minSdk 30`,
`targetSdk 36`, `compileSdk 37`, `arm64-v8a` only. Needs **JDK 17+**.

```bash
./gradlew test            # core logic, JVM, seconds — run this constantly
./gradlew assembleDebug   # APK
./gradlew build           # both variants + lint (slow)
```

`local.properties` is gitignored; Android Studio writes it, or create it with
`sdk.dir=<path to your Android SDK>`.

**Retune the running app without compiling** (the whole point of `policy.json`,
see §7):

```bash
adb push policy.json /data/local/tmp/
adb shell run-as com.showhow cp /data/local/tmp/policy.json files/policy.json
```

## 5. Layout and file ownership

Git conflicts happen when two people edit the same file. The team's rule is
that no two people ever edit the same file. **An agent inherits the ownership
of whoever is driving it.** If a change belongs in someone else's folder, stop
and say so instead of making it.

| Path | Owner | What lives there |
|---|---|---|
| `app/src/main/java/com/showhow/core/` | Kshitij | Gate, step cutter, mode engine, policy. Zero `android.*` imports. |
| `app/src/main/java/com/showhow/capture/` | Kshitij | AudioRecorder, CameraController, MotionSource |
| `app/src/main/java/com/showhow/ai/` | Kshitij | Asr, Captioner, GestureSource, SceneCheck + fakes |
| `app/src/main/java/com/showhow/data/` | Kshitij | Guide, Step, GuideStore, PolicyRepository |
| `app/src/main/java/com/showhow/ui/` | Anushka | Every Compose screen, theme, all visuals |
| `app/src/main/assets/policy.json` | Ayaan | Every tunable number, both word lists |
| `docs/`, `README.md` | Ayaan (except `docs/ARCHITECTURE.md`, Kshitij) | Docs, pitch, demo notes, Office Kit log |
| `app/build.gradle.kts`, `AndroidManifest.xml`, `gradle/libs.versions.toml` | **Kshitij only** | Nobody else touches these, ever |

### Which teammate am I working as?

An agent cannot guess this, and guessing wrong means editing someone else's
file. Read it from git — the same identity that will author the commit:

```bash
git config user.name     # Kshitij Desai | Anushka Unde | Ayaan Memon
```

Match that name against the table above and stay inside those paths. **If it
comes back empty, or does not match a row, stop and ask who you are working as
before writing anything.** An empty `user.name` also means commits land as
`unknown`, which makes the ownership rule unenforceable after the fact.

Each person sets it once, on their own machine:

```bash
git config --global user.name  "Your Name"
git config --global user.email "your@email.com"
```

`git log --format='%an %ae' -5` shows who has actually been committing, which
is the fastest way to check the rule is holding.

## 6. Current state — what is real and what is left

Do not assume a class does what its name suggests. Verified against the code,
not remembered.

**Real and tested** (54 JVM tests, all passing)

- `core/AdaptiveGate` — tracks the room's noise floor so a fixed dB threshold
  cannot fail in a fan-noise kitchen. Eats `-Infinity`/NaN from a dead mic.
- `core/StepCutter` — silence runs ≥ `pauseMs` become cuts at the pause
  midpoint; short utterances merge forward; hard cap at `maxSteps`.
- `core/LinkWordConfirmer` — real, and passed to `StepCutter` by the ViewModel.
  Abstains when there is nothing to vote with, because "no opinion" has to mean
  pass-through or the app hands back one enormous step.
- `core/mapSnapsToSteps` — photos are paired to final steps **by time**, each
  photo offered to its best step. This is the fix for the old photo-indexing
  defect; `SnapMapperTest` is what keeps it fixed.
- `core/ModeEngine` — EASY/HANDS/TALK/TAP from four booleans, Schmitt triggers
  plus a dwell timer so nothing flickers. Drives `PlayerScreen`, not just Debug.
- `core/SceneHash`, `core/DwellLatch`, `core/Policy`, `core/PolicyStore`,
  `data/PolicyRepository` — live reload, and `PolicyAssetTest` fails the build
  if `policy.json` drifts out of sync with `Policy.kt`.
- `capture/` — all three sources work. `data/GuideStore` — plain folders.
- `ui/` — five screens wired end to end; the Player binds the camera and reads
  gestures, mode, detections and scene similarity.

**Real models, no fakes anywhere.** `Fakes.kt` is gone. Every model here is a
gitignored file under `filesDir/models/`, loads down a delegate ladder, and
returns *nothing* when it is absent — never canned data.

- `ai/DeviceAsr` → `ai/VoskAsr` — the system on-device recogniser, Vosk as
  fallback. One Vosk model per language; language is picked before recording.
- `ai/ObjectDetectSource` + `ai/DetectorCaptioner` — real boxes, real scores,
  and captions that are the labels the detector actually returned.
- `ai/MediaPipeGestureSource`, `ai/RealSceneCheck`, `ai/DeviceNarrator`.
- `ai/Coach` — the on-device Gemma that rewrites the expert's steps into an
  English instruction and answers the learner's questions. See
  `docs/ARCHITECTURE.md` §11 for the model files and how to install them.

**What is actually left**

- **No `models/coach.task` exists on any phone, and none has ever been loaded.**
  Every coach path is written, compiled and tested against canned replies, and
  none of it has run against a real model. Load time, inference time and memory
  use are unmeasured. This is the single largest unknown in the project.
- `userFar` is always false: `feedFaceSize` has no caller, so that `ModeEngine`
  branch is unreachable. It needs a face detector, and §2's fourth constraint
  says we do not guess at what we cannot honestly measure.
- The object detector is generic COCO -- "laptop", "keyboard", "person". It has
  no label for a screw, a RAM module, an SSD or a screwdriver and never will
  until a different `.tflite` is on the phone. Nothing in the app claims
  otherwise; `AnswerEvidence.VISUAL_FACT` is capped by what the detector
  actually returned.
- `com.google.android.datatransport` arrives transitively through MediaPipe and
  registers a receiver and a JobService in the merged manifest. It cannot send
  anything -- there is no `INTERNET` permission -- but the guarantee rests on
  the missing permission, not on the library's absence. See §2.

**How to check any of this yourself**

```bash
./gradlew test          # 187 JVM tests, including the AI evaluation harness
```

The harness (`app/src/test/java/com/showhow/eval/`) runs fourteen laptop-repair
scenarios plus two adversarial ones through the real parsers and grounding, with
no model file, and prints a PASS table. It evaluates *this app's* handling of a
model's output -- never how good the model is, which no JVM test can tell you.

## 7. The one thing that makes this project survivable

For roughly 10.5 hours of the event nobody can compile ("Red Light"). The only
compile windows are Sat 11:00–13:00, Sat 15:30–16:30 and Sun 01:00–06:30.

Therefore **no behaviour may be hardcoded**. Every number lives in
`core/Policy.kt` with a matching entry in `app/src/main/assets/policy.json`.
On first run the app copies the asset into `filesDir/policy.json` and from then
on reads **only** that file, watching the *directory* with a `FileObserver`
(editors and adb replace the inode, so a watch on the file itself would die).
Push a new file and the app retunes live, no restart. A malformed file is
logged and ignored; the last good policy stays in force.

If you add a knob, add it in **both** places, or the team cannot tune it during
Red Light.

## 8. House style

- Boring over clever. Shortest working diff. Delete before you add.
- Comments explain *why* a number or a branch exists, not what the line does.
  Match the density already in the file — it is deliberate.
- New non-trivial logic in `core/` gets a JVM test in
  `app/src/test/java/com/showhow/core/`. UI does not.
- A deliberate shortcut with a known ceiling gets a `ponytail:` comment naming
  the ceiling and the upgrade path, the way `AdaptiveGate` and `StepCutter`
  already do.
- Never add a dependency for something a few lines can do. The debug APK is
  91 MB and every megabyte of it is a model library that actually runs.

## 9. Git rules (non-negotiable)

1. Work directly on `main`. Branch only for an experiment you might throw away.
2. `git pull` before you push. Every time.
3. Commit every 30–60 minutes with a plain message.
4. **Never `git push --force`.**
5. **Never commit a model file.** `.task`, `.onnx`, `.tflite`, `.gguf` and
   `**/models/` are gitignored. If `git status` shows one, stop.
6. Only push code that builds. Run `./gradlew test` first.

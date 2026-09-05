# ShowHow — team workflow

Source of truth for who owns what, how git is used, and what the weekend looks
like. Condensed from the team guide so an agent (or a teammate at 3 am) can
read it without opening a PDF. Start at [`../AGENTS.md`](../AGENTS.md).

Team: **Kshitij Desai** (on-device AI, systems) · **Anushka Unde** (UI/UX) ·
**Ayaan Memon** (Office Kit, content, demo, pitch).

---

## 1. The one idea that prevents almost every problem

Git conflicts happen when two people edit the same file. Almost nothing else
causes them. So: **no two people ever edit the same file.** Hold that line and
git stays boring, and boring is what you want at three in the morning.

| Path | Owner |
|---|---|
| `app/src/main/java/com/showhow/core/` | Kshitij |
| `app/src/main/java/com/showhow/capture/` | Kshitij |
| `app/src/main/java/com/showhow/ai/` | Kshitij |
| `app/src/main/java/com/showhow/data/` | Kshitij |
| `app/src/main/java/com/showhow/ui/` | Anushka |
| `app/src/main/assets/policy.json` | Ayaan |
| `docs/`, `README.md` | Ayaan (`docs/ARCHITECTURE.md` is Kshitij's) |
| `app/build.gradle.kts`, `AndroidManifest.xml`, `gradle/libs.versions.toml` | Kshitij only, nobody else, ever |

**The rule:** if you need something changed in someone else's folder, ask them.
Do not do it yourself. Ten seconds of asking beats forty minutes of untangling.
An agent inherits the ownership of whoever is driving it — see
[`../agents/README.md`](../agents/README.md).

## 2. Git

The five commands that are actually needed this weekend:

```bash
git pull                     # get everyone else's latest work
git add .                    # stage your changes
git commit -m "what I did"   # save them
git push                     # send them to GitHub
git status                   # what is going on right now
```

**Rhythm.** `git pull` every time you sit down. `add` + `commit` + `push` every
time something works, roughly every 30–60 minutes. A commit is a save point.
Commit once every six hours and a crash costs six hours; commit every thirty
minutes and it costs thirty.

**Not negotiable**

1. Never `git push --force`. It deletes other people's work.
2. Never commit a model file. `.task`, `.onnx`, `.tflite`, `.gguf` and
   `**/models/` are gitignored. If `git status` shows one, stop and tell
   Kshitij.
3. Only push code that builds. Run `./gradlew test` first.
4. Pull before you push. Always.

**Conflicts.** Do not guess. Say it out loud in the room. The escape hatch that
always works and is nothing to be embarrassed about: copy your changed file to
the desktop, delete the folder, clone fresh, paste the file back, commit, push.
Four minutes, has never once made things worse.

**Branches.** Work directly on `main`. With file ownership in place that is
safe and far faster than PRs nobody has time to review. Branch only for an
experiment you might throw away:

```bash
git checkout -b anushka-new-player-layout
# ...work, commit, push as normal...
git checkout main
```

## 3. Red Light / Green Light

The constraint that shapes everything: for roughly **10.5 hours the laptop is
closed as a build machine**. Compile windows are only:

| Window | Length | What it is for |
|---|---|---|
| Sat 11:00–13:00 | 2 h | Models onto the phone and loading |
| Sat 15:30–16:30 | 1 h | Whatever is genuinely broken. Do not fill it in advance. |
| Sun 01:00–06:30 | 5.5 h | All heavy integration |

Everything else is `policy.json` tuning, testing on the phone, recording demo
guides, and pitch work.

**Fifteen minutes before every Green window opens, decide out loud what gets
compiled in it.** Green time is the scarce resource. Do not discover at 15:45
that the hour went on something Red Light could have covered.

This is why nothing may be hardcoded: during Red Light the only thing anyone
can change is `policy.json`, pushed over Office Kit file transfer or adb.

## 4. Roles

### Kshitij — the spine

Writes most of the Kotlin: `core/`, `capture/`, `ai/`, `data/`, plus the build
files and the manifest. Owns the technical document and the answers to hard
jury questions — nobody else can defend the adaptive gate. During Red blocks:
tune `policy.json` with Ayaan, test on the phone, hunt bugs fixable by config
alone.

### Anushka — everything the judge touches

Owns `ui/` completely. Kshitij hands over plain working screens; she makes them
worth looking at. Priority order: **PlayerScreen** (four visual states —
TAP, TALK, HANDS, EASY — plus the mode reason bar pinned to the bottom; TALK
must be readable across a room, HANDS must be usable with no touching at all),
then **ShowScreen** (viewfinder, live mic meter, step timeline building up as
the expert talks), then **ReviewScreen** (join, split, re-record — the pitch
claims 20 seconds, so make that true), then **LibraryScreen**.

Carry the deck's visual language into the app: paper background, black display
type, blue accents, so the slides and the product look like one thing. During
Red blocks: design and sketch, ready to type the moment Green opens.

### Ayaan — the two scoring categories nobody else has time for

Office Kit usage is **10%**, read off HackTracker device data — real counts and
durations, so it cannot be faked on Sunday morning. Demo and presentation is
another **10%**. End product quality is **30%**, judged on a 2.5-minute live
demo, and the five guides recorded on Sunday morning are the insurance for it.

Five deliverables, and nothing else is his job:

| # | Deliverable | Where |
|---|---|---|
| 1 | Every tunable number, tuned | `app/src/main/assets/policy.json` |
| 2 | Hindi and Marathi linking-word lists | inside `policy.json` |
| 3 | Office Kit usage log | `docs/officekit-log.md` |
| 4 | Demo runbook + five recorded guides | `docs/demo-runbook.md` |
| 5 | Pitch script + ~100 second video | `docs/pitch.md` |

At check-in, three things only he does: enable developer options **and** USB
debugging (on iQOO there is a second toggle, "USB debugging (security
settings)", which usually needs a vivo account sign-in — do it while there is
still network); pair Office Kit and confirm all four functions (screen mirror,
shared clipboard, file transfer, remote control); and ask the organisers out
loud whether the laptop may compile and deploy at all during Red Light.

**Office Kit log format** — one line per use, plain text, from hour one, since
steady use across all 30 hours scores better than a burst at the end:

```
Sat 11:20  file transfer  gesture_recognizer.task -> phone
Sat 13:05  screen mirror  on, 40 min, testing step cutter
Sat 17:12  file transfer  policy.json v4 -> phone
```

## 5. The pitch, same shape every time

1. **The problem, in one sentence.** Someone in every workshop knows how the
   job is done. Writing it down takes hours nobody has.
2. **Show Mode.** The expert does the job once and talks through it. Ninety
   seconds later it is a guide, in her own recorded voice.
3. **Guide Mode.** The next person runs it with full hands. The phone reads the
   room and picks how to help. They never choose a mode.
4. **The honest part.** Say plainly what we refuse to claim: gloves, driving,
   whether someone is elderly, whether the work is correct. Anything the phone
   cannot honestly measure is a setting, not a guess.
5. **The proof.** No INTERNET permission, shown in the app info screen in four
   seconds.

**Do not cut point four.** It is the one most teams do not have. A team that
states its limits reads as more credible, not less, and technical judges notice
immediately.

## 6. Rhythms

- Every four hours, one person says out loud what the demo would look like if
  we stopped right now. If the answer is "nothing works", cut scope
  immediately, not at hour 28.
- Whoever is not blocked helps whoever is. Hold the phone, run the tests, time
  things, read error messages aloud. There is no such thing as waiting on a
  three-person team.
- Sleep in shifts. Ayaan is pitching, so Ayaan sleeps most (Sun 01:00–06:30).

**The short version:** only touch your own folders. Pull before you push.
Commit every thirty minutes. Never force push. Never commit a model file. If
git scares you, say so out loud instead of guessing.

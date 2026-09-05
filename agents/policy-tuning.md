---
name: policy-tuning
role: Tune the app's behaviour without compiling
owner: Ayaan
writes: app/src/main/assets/policy.json
never_touches: any .kt file, build files, ui/
description: Use for every tunable number and both linking-word lists — the only thing anyone can change during Red Light. Includes what each knob does and which way to move it when a symptom shows up on the phone.
---

# policy-tuning

Read [`../AGENTS.md`](../AGENTS.md) §7 first. This role changes how the app
behaves **without a compiler**, which for ~10.5 hours of the event is the only
kind of change anyone can make.

## Paths

- Write: `app/src/main/assets/policy.json` — and only the *values*.
- Adding or renaming a **key** is a Kotlin change (`core/Policy.kt`) and
  belongs to `core-systems`. Ask; do not invent a key, it will be silently
  ignored (`ignoreUnknownKeys = true`).
- Never open a `.kt` file, `build.gradle.kts`, or the manifest.

## How a change reaches the phone

```bash
adb push policy.json /data/local/tmp/
adb shell run-as com.showhow cp /data/local/tmp/policy.json files/policy.json
```

The app watches the directory and reloads instantly — no restart, no rebuild.
The asset in the repo is the **seed only**: it is copied once on first run, and
after that the phone reads only its own copy. So editing the repo file changes
what a *fresh install* starts with; pushing changes what *this* phone does now.
Do both, or a good value is lost at the next reinstall.

A malformed file is logged and ignored and the last good values stay in force —
so a typo costs you nothing, but check the Debug screen: it prints the parse
error and every live value.

During the event, move the file by **Office Kit file transfer** rather than a
cable where you can, and log each transfer in `docs/officekit-log.md`. Same
result, and it feeds the 10% usage score.

## The knobs

### Gate — "is this speech or is this the room?"

| Key | Default | What it does |
|---|---|---|
| `gateFallCoef` | 0.25 | How fast the noise floor drops toward a quiet sample. Fast on purpose: 0 to a −20 dB fan in about half a second. |
| `gateRiseCoef` | 0.004 | How slowly it creeps back up, so a long sentence cannot drag it along. |
| `speechMarginDb` | 9.0 | How far above the floor a sample must be to count as speech. **The main sensitivity dial.** |
| `gateMinDb` / `gateMaxDb` | −45 / −6 | Hard clamp on the threshold, whatever the floor does. |

### Cutter — "where does one step end?"

| Key | Default | What it does |
|---|---|---|
| `pauseMs` | 1200 | Silence longer than this is a step boundary. **The main step-count dial.** |
| `minUtteranceMs` | 2500 | Anything shorter merges into the step after it. |
| `maxSteps` | 12 | Hard cap. Everything past it lands in the final step. |

### Mode engine — "how should the phone help right now?"

| Key | Default | What it does |
|---|---|---|
| `inHandEnterVar` / `inHandExitVar` | 0.09 / 0.05 | Accelerometer variance for "phone is in a hand". Enter ≠ exit so a value on the line cannot flicker. |
| `roomLoudEnterDb` / `roomLoudExitDb` | −26 / −32 | Same idea for "the room is loud" → HANDS mode. |
| `dwellMs` | 400 | A candidate mode must survive this long before it is committed. Raise it if the screen changes its mind on stage. |

Keep `enter` and `exit` apart, and keep them the right way round
(`inHandEnter > inHandExit`, `roomLoudEnter > roomLoudExit`). Setting them
equal removes the hysteresis and the demo starts flickering.

### Word lists

`linkWordsHi` and `linkWordsMr` — the ways a person says "and now the next
thing". Start from `phir, ab, uske baad, next, then` (Hindi) and
`mag, ata, tyanantar, next, then` (Marathi). Aim for **15–20 Hindi, 10
Marathi**. Best way to collect them: walk around the venue and ask people to
explain a process out loud, then write down how they moved between steps.

Lowercase, no punctuation. A multi-word phrase like `uske baad` is fine.

## Symptom → knob

| What you see on the phone | Move this |
|---|---|
| One enormous step, no cuts at all | `speechMarginDb` down, or `pauseMs` down |
| Way too many tiny steps | `pauseMs` up, `minUtteranceMs` up |
| Steps cut mid-sentence | `pauseMs` up |
| Fan/AC room reads as constant speech | `speechMarginDb` up (the floor tracker should handle it — check `floor` on the Debug screen first) |
| Gate never fires, meter looks dead | `gateMinDb`/`gateMaxDb` clamp is too tight |
| Mode flips back and forth on stage | `dwellMs` up, and widen the enter/exit gap |
| Never leaves TAP when the phone is on a counter | `inHandEnterVar` down |
| Never goes HANDS in a noisy hall | `roomLoudEnterDb` up (less negative is louder) |
| A short "and that's it" tail became its own step | `minUtteranceMs` up |

## Method

Change **one number at a time**, push, watch the Debug screen, record what
happened. Keep a numbered trail (`policy.json v4`) in the Office Kit log so a
good value can be recovered after a bad one. If two changes go over together
and behaviour improves, you have learned nothing.

## Definition of done

- Valid JSON (a trailing comma is the usual killer — the Debug screen will say
  so).
- Every value tested on the actual phone in the actual room, not reasoned about.
- Repo copy and phone copy agree.
- Word lists at 15–20 Hindi and 10 Marathi, gathered from real people.

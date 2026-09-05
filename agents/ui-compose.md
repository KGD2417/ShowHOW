---
name: ui-compose
role: Every Compose screen, the theme, all visuals
owner: Anushka
writes: app/src/main/java/com/showhow/ui/, app/src/main/res/
never_touches: core/, capture/, ai/, data/, build files, assets/policy.json
description: Use for PlayerScreen, ShowScreen, ReviewScreen, LibraryScreen, DebugScreen, theme and layout work. Everything the judge looks at.
---

# ui-compose

Read [`../AGENTS.md`](../AGENTS.md) first, and §9 of
[`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) for what each screen does
today. This role owns everything the judge touches.

## Paths

- Write: `app/src/main/java/com/showhow/ui/`, `app/src/main/res/`
- Read, never change: `core/`, `capture/`, `ai/`, `data/`, build files,
  `assets/policy.json`
- If a screen needs data the ViewModel does not expose, **ask** — do not add it
  yourself. Ten seconds of asking beats forty minutes of untangling.

## What you already have to work with

`ShowHowViewModel` exposes, as `StateFlow`s you collect with
`collectAsStateWithLifecycle()`:

| Flow | Contents |
|---|---|
| `screen` | current `Screen` (navigate with `vm.go(Screen.X)`) |
| `debug` | one `DebugState`: `levelDb`, `gateDb`, `floorDb`, `accelVariance`, `mode`, `reason`, `switches`, `recording`, `elapsedMs`, `samples`, `liveCuts`, `policyError` |
| `library` | `List<Guide>` |
| `easyMode` | the user override |
| `policy` | the live `Policy` — every tunable number |

Plus `vm.guides` (load a guide, its folder, `take.wav`, per-step photos),
`vm.startRecording()`, `vm.stopRecording { id -> }`, `vm.attachCamera(...)`,
`vm.setEasyMode(...)`.

Navigation is a sealed interface plus a `when` in `ShowHowHost.kt`. Keep it
that way — Navigation-Compose buys nothing for five screens and costs an
argument about routes at 2 am.

## Laws for this role

1. **No disabled buttons. Anywhere.** Warnings advise, they never block. A
   `Step.warning` or a low scene-check score is a line of text next to a button
   that still works.
2. **The user never picks a mode.** The phone reads the room; the UI reflects
   the decision and shows the reason. There is no mode selector, only the Easy
   Mode switch, which is a *setting*, not a guess.
3. **Always show the reason.** Every mode switch carries a human string like
   `"HANDS <- room is loud (-24.3 dBFS)"`. Put it in a reason bar pinned to the
   bottom of the Player. It is the honesty of the product made visible, and the
   jury notices.
4. **Never hardcode a number that belongs to `policy`.** Thresholds and timings
   come from `vm.policy`. Spacing, type scale and colour are yours.
5. **Do not touch the Debug screen's content.** Restyle it if you like, but
   every number on it is load-bearing at 3 am. Nothing gets removed.
6. **Compose basics are enough.** `Column`, `Row`, `Box`, `Text`, `Button`,
   `Modifier`, `remember`. That genuinely builds all four screens.

## Priority order — do not reorder this

1. **`PlayerScreen`** — the most important screen in the product; it is what
   the jury watches. Needs four visual states plus the reason bar:
   - **TAP** — ordinary touch, held phone, quiet room.
   - **TALK** — phone flat on a counter or user far away. Text must be readable
     **across a room**: huge type, one instruction at a time.
   - **HANDS** — loud room or unclear speech. Must be usable with **no touching
     at all**: big targets, obvious dwell/gesture affordances, nothing that
     needs precision.
   - **EASY** — the user's own override, the simplest possible layout.
   Today it has photo + caption + "Hear it" + Back/Next. That flow works;
   make it worth looking at.
2. **`ShowScreen`** — viewfinder, a real live mic meter showing level against
   the gate (the numbers are already in `debug`), and a step timeline building
   up as the expert talks (`debug.liveCuts` ticks up at each boundary).
3. **`ReviewScreen`** — join, split, re-record. The pitch says a person fixes
   the cuts in twenty seconds, so this screen has to make that actually true.
   It is read-only today.
4. **`LibraryScreen`** — the list of guides. Least important. Do it last.

## Visual language

Carry the deck into the app: **paper background, black display type, blue
accents**, so the slides and the product read as the same thing. `Theme.kt` is
deliberately stock Material 3 — replacing it is your call and your file.

## Working during Red Light

You cannot compile for ~10.5 hours (see `../docs/WORKFLOW.md`). Sketch layouts,
fix type scales and colours, and have the code ready to paste the moment a
Green window opens. Fifteen minutes before each window, say out loud what gets
compiled in it.

## Definition of done

- `./gradlew assembleDebug` succeeds and the app still launches.
- No disabled control anywhere, and no mode picker.
- Every screen reads its numbers from `vm.debug` / `vm.policy`, none hardcoded.
- HANDS state is genuinely operable without touching the screen; TALK state is
  legible from across a room — check both on the phone, not in a preview.

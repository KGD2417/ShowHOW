---
name: demo-pitch
role: Demo runbook, recorded guides, Office Kit log, pitch script and video
owner: Ayaan
writes: docs/ (except docs/ARCHITECTURE.md), README.md
never_touches: any .kt file, build files, ui/
description: Use for the 20% of the score nobody else has time for — Office Kit usage evidence, the demo runbook, the five recorded guides, and the pitch. Also protects the 30% product-quality score, which is judged on a live demo.
---

# demo-pitch

Read [`../AGENTS.md`](../AGENTS.md) and [`../docs/WORKFLOW.md`](../docs/WORKFLOW.md)
first. This role exists because two scoring categories, 20% of the total,
belong to nobody else — and the other two people will be writing code until the
last hour.

## What is at stake

| Category | Weight | How it is judged |
|---|---|---|
| Office Kit usage | 10% | HackTracker device data — real counts and durations. **Cannot be faked on Sunday morning.** |
| Demo and presentation | 10% | Humans, 3–5 minute pitch |
| End product quality | 30% | A 2.5-minute **live demo**. If the recording fumbles on stage, the 30% goes with it. |

The five guides recorded on Sunday morning are the insurance for that 30%.

## The five deliverables — nothing else is this role's job

| # | Deliverable | Where |
|---|---|---|
| 1 | Every tunable number, tuned | `app/src/main/assets/policy.json` → see [`policy-tuning.md`](policy-tuning.md) |
| 2 | Hindi and Marathi linking-word lists | inside `policy.json` |
| 3 | Office Kit usage log | `docs/officekit-log.md` |
| 4 | Demo runbook + five recorded guides | `docs/demo-runbook.md` |
| 5 | Pitch script + ~100 second video | `docs/pitch.md` |

## Office Kit log

Plain text, one line per use, **from hour one**. Steady use across all 30 hours
scores better than a burst at the end, and it feeds straight into the technical
document.

```
Sat 11:20  file transfer  gesture_recognizer.task -> phone
Sat 11:34  file transfer  gemma-3n-e2b.task -> phone
Sat 13:05  screen mirror  on, 40 min, testing step cutter
Sat 17:12  file transfer  policy.json v4 -> phone
```

Every model file and every `policy.json` revision moves by **file transfer, not
cable**. Same result, and it starts the usage clock in hour one. Keep screen
mirror running during debugging sessions — it is genuinely useful *and* it
counts.

At check-in, three things only this role does: enable developer options **and**
USB debugging (on iQOO there is a second toggle, "USB debugging (security
settings)", which usually needs a vivo account sign-in — do it while there is
still network); pair Office Kit and confirm all four functions work (screen
mirror, shared clipboard, file transfer, remote control); and ask the
organisers **out loud** whether the laptop may compile and deploy at all during
Red Light, or only run Office Kit. The whole schedule depends on that answer,
and it has to come from a person, not the website.

## The demo runbook

`docs/demo-runbook.md` is numbered steps a stranger could follow, because on
Sunday you will be tired. It must cover:

- The exact device state before the demo: **airplane mode on** (it proves the
  offline claim and removes every notification risk), volume up, screen
  brightness up, the right guide already in the library.
- The exact taps, in order, and roughly how long each takes.
- The four-second proof: open Android app info and show there is **no INTERNET
  permission**.
- What to do when something goes wrong mid-demo, and which pre-recorded guide
  to fall back to.

Run the whole thing in airplane mode **at least three times, timed**. It has to
fit inside 2.5 minutes.

Then break it on purpose, before a judge does it for you: cover the mic, move
the phone mid-step, make the room loud, put the phone flat on the table. Each of
those should visibly change the mode and print a reason — that is the product
working, not failing, and knowing it in advance turns a scary question into the
best moment of the pitch.

## The five recorded guides

Recorded Sunday 06:30–09:00, properly, start to finish. Practise with throwaway
guides on Saturday night first, so you learn what makes a bad one before it
matters. A good guide: a real task, clear pauses between steps (the cutter is a
pause detector — the expert should breathe between steps, not race), a stable
phone, and a scene that looks different at each step so the photos are worth
having.

## The pitch — same shape, every time

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

**Never cut point four.** Most teams do not have it. Stating limits reads as
more credible, not less, and technical judges notice immediately.

Write a bad first draft tonight. Editing something bad on Saturday is far
easier than starting from blank. Say it out loud twenty times before the final
round, not five. At Eval 1, write down every question you could not answer well
and hand the list to Kshitij.

## The technical answers to have ready

Be able to explain these to a stranger who is judging you. If you cannot yet,
ask until you can — `docs/ARCHITECTURE.md` is the source.

- **Why an adaptive gate and not a fixed threshold?** A ceiling fan floors a
  room around −20 dBFS. A gate nailed at −38 dB reads that as continuous speech,
  finds no pause, and the whole take comes back as one enormous step. So the
  gate learns the room's floor instead: it falls fast into a quiet room, rises
  slowly, and freezes while someone is speaking so a long sentence cannot drag
  it up.
- **Why is there no AI in the step cutter?** Because a pause detector plus a
  word list runs on a five-year-old phone in milliseconds, and a video model
  does not. The product is for workshops, not flagships.
- **How does the phone choose a mode?** Four booleans and a decision table, with
  hysteresis so a value on the line cannot flicker and a 400 ms dwell so nothing
  switches twice a second. Every switch carries the reason, on screen.
- **What does it refuse to guess?** Gloves, driving, age, correctness. Those are
  settings, never inferences.
- **Where does the data go?** Nowhere. There is no INTERNET permission; a guide
  is a folder you can copy off the phone with a file manager.

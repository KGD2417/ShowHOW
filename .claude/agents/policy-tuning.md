---
name: policy-tuning
description: Tune ShowHow's behaviour without compiling by editing app/src/main/assets/policy.json — gate sensitivity, step-cut timing, mode-engine hysteresis and dwell, and the Hindi/Marathi linking-word lists. Use when a symptom on the phone needs a config fix, especially during Red Light when nobody can build.
---

Read `AGENTS.md` §7, then `agents/policy-tuning.md`, and follow it for this
task. It carries the full knob reference and a symptom-to-knob table.

Non-negotiable: values only — adding or renaming a **key** is a Kotlin change
in `core/Policy.kt` and belongs to core-systems (an unknown key is silently
ignored). Change one number at a time, keep enter/exit thresholds apart, and
update both the repo copy and the phone copy. Never open a `.kt` file.

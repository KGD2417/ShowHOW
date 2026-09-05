# Copilot instructions — ShowHow

The full instructions for this repository are in **`AGENTS.md`** at the root.
Read it, plus the role file in `agents/` that matches what you are doing, and
`docs/ARCHITECTURE.md` for the technical detail. This file is only a pointer,
so the rules stay in one place for every agent.

Non-negotiables, expanded in `AGENTS.md`:

- ShowHow is a fully offline Android app. **No `INTERNET` permission** — the
  manifest strips it at merge. Never add a network call, a URL, or a dependency
  that needs one.
- The step cutter is a pause detector plus a word list. **No ML in that path.**
- The mode engine is a plain decision table. **No AI in it.**
- Warnings advise, they never block. No disabled controls anywhere.
- `app/src/main/java/com/showhow/core/` imports zero `android.*` classes.
- Every tunable number lives in `core/Policy.kt` **and**
  `app/src/main/assets/policy.json`. Never hardcode one.
- Kotlin + Compose, `minSdk 30`, JDK 17+. `./gradlew test` runs the core tests
  in seconds — run it before claiming anything works.
- File ownership is strict: stay inside the paths your role owns
  (`agents/README.md`).

# CLAUDE.md

This project's instructions live in **[`AGENTS.md`](AGENTS.md)** — read it
before touching anything. It is tool-agnostic on purpose; this file only exists
so Claude Code loads it automatically.

Then pick the role you are working as, from [`agents/`](agents/README.md):

| Role | Owns | Subagent |
|---|---|---|
| Pure logic, capture, storage | `core/`, `capture/`, `data/` | `core-systems` |
| Real models behind the fakes | `ai/` | `ai-integration` |
| Every Compose screen | `ui/` | `ui-compose` |
| Tuning without compiling | `assets/policy.json` | `policy-tuning` |
| Runbook, Office Kit log, pitch | `docs/` | `demo-pitch` |
| The gate before a push | nothing, read-only | `build-verify` |

Deep technical map: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
Team, git and schedule rules: [`docs/WORKFLOW.md`](docs/WORKFLOW.md).

Quick reminders, all expanded in `AGENTS.md`:

- No `INTERNET` permission, ever. No ML in the step cutter. No AI in the mode
  engine. Warnings advise, they never block.
- `core/` imports zero `android.*`.
- Every number lives in `core/Policy.kt` **and** `app/src/main/assets/policy.json`.
- Stay inside the paths your role owns; ask instead of editing someone else's.
- `./gradlew test` before you claim anything works.

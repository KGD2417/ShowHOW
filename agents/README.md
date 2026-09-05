# Role agents

Six role definitions, one per kind of work on ShowHow. They are plain Markdown
with a small YAML header, so **any** coding agent can use them — there is
nothing Claude-specific in this folder.

Read [`../AGENTS.md`](../AGENTS.md) first. These files add the role: what you
own, what you must not touch, what "done" means for that role.

| File | Role | Owner | Writes to |
|---|---|---|---|
| [`core-systems.md`](core-systems.md) | Pure logic, capture, storage | Kshitij | `core/`, `capture/`, `data/`, `app/src/test/` |
| [`ai-integration.md`](ai-integration.md) | Replace the fakes with real models | Kshitij | `ai/`, and the one wiring line in the ViewModel |
| [`ui-compose.md`](ui-compose.md) | Every screen the judge touches | Anushka | `ui/` only |
| [`policy-tuning.md`](policy-tuning.md) | Tune behaviour without compiling | Ayaan | `app/src/main/assets/policy.json` |
| [`demo-pitch.md`](demo-pitch.md) | Runbook, Office Kit log, pitch | Ayaan | `docs/` (not `docs/ARCHITECTURE.md`) |
| [`build-verify.md`](build-verify.md) | The gate before any push | Kshitij | nothing — read-only, reports |

## How to use these with your tool

**Claude Code** — thin stubs in `.claude/agents/` already point here, so
`@core-systems`, `@ui-compose`, etc. work as subagents. Or just say
"follow `agents/ui-compose.md`".

**Cursor / Windsurf / Zed / Continue** — open the role file in context, or
paste its body into your rules panel for the session. `AGENTS.md` at the repo
root is picked up automatically by tools that support it.

**Codex / Copilot / Aider / any chat** — start the session with:

> Read `AGENTS.md` and `agents/<role>.md`, then follow them for this session.

**Nothing at all** — they read fine as a human checklist.

## Rules that apply to every role

0. **Know who you are.** Run `git config user.name` and match it against the
   ownership table in `AGENTS.md` §5 — that is the identity that will author
   the commit. If it is empty or matches nobody, ask before writing anything.
1. **Stay inside your paths.** The whole git strategy is that no two people
   edit the same file. If the fix belongs in someone else's folder, say so and
   stop; do not make it.
2. **Never break the four hard constraints** in `AGENTS.md` §2. No `INTERNET`
   permission, no ML in the cutter, no AI in the mode engine, no warning that
   blocks.
3. **Never hardcode a number.** It goes in `core/Policy.kt` *and*
   `app/src/main/assets/policy.json`, or the team cannot tune it during Red
   Light.
4. **Run `./gradlew test` before you claim anything works**, and never push
   code that does not build.
5. **Report honestly.** If a test fails, say so with the output. If you skipped
   part of the task, say which part and why.

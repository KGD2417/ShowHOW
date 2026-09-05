---
name: ui-compose
description: Every Compose screen, the theme and all visuals for ShowHow — PlayerScreen, ShowScreen, ReviewScreen, LibraryScreen, DebugScreen. Use for layout, styling, navigation and anything the judge looks at. Owns ui/ and res/ only.
---

Read `AGENTS.md`, then `agents/ui-compose.md`, and follow it for this task.
Screen-by-screen state is in `docs/ARCHITECTURE.md` §9.

Non-negotiable: no disabled controls anywhere (warnings advise, never block);
the user never picks a mode, the phone decides and shows the reason; thresholds
come from `vm.policy`, never hardcoded; the Debug screen keeps every number it
has. Do not edit `core/`, `capture/`, `ai/`, `data/` or the build files — if a
screen needs data the ViewModel does not expose, ask.

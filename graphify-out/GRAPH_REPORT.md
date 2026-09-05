# Graph Report - .  (2026-09-05)

## Corpus Check
- Corpus is ~21,609 words - fits in a single context window. You may not need a graph.

## Summary
- 416 nodes · 705 edges · 24 communities (23 shown, 1 thin omitted)
- Extraction: 86% EXTRACTED · 14% INFERRED · 0% AMBIGUOUS · INFERRED: 99 edges (avg confidence: 0.86)
- Token cost: 267,490 input · 0 output

## Community Hubs (Navigation)
- Project Constraints And Ownership
- Scene Check And Architecture Rationale
- Guide Storage And Navigation
- AI Interfaces And Fakes
- Compose Screens And UI Rules
- Policy Store And Reload Tests
- Step Cutter And Its Tests
- Adaptive Gate And Motion Source
- Mode Engine And Its Tests
- Audio Recorder And Model Integration
- Scene Hash Image Similarity
- Demo, Pitch And Policy Tuning Roles
- CameraX Capture Controller
- Core Laws And Verification
- Policy Tunable Numbers
- Offline Proof And Demo Runbook
- Policy Hot Reload Watcher
- App Entry And Theme
- Build Verify And Red Light
- Gradle Wrapper Script
- GuideStore Doc Reference

## God Nodes (most connected - your core abstractions)
1. `ShowHowViewModel` - 38 edges
2. `Core Systems Role` - 26 edges
3. `ModeEngine` - 19 edges
4. `StepCutter` - 18 edges
5. `AdaptiveGate` - 17 edges
6. `UI Compose Role` - 17 edges
7. `Policy` - 15 edges
8. `GuideStore` - 15 edges
9. `AI Integration Role` - 15 edges
10. `Policy Tuning Role` - 15 edges

## Surprising Connections (you probably didn't know these)
- `Core Systems Role` --references--> `AudioRecorder`  [INFERRED]
  agents/core-systems.md → app/src/main/java/com/showhow/capture/AudioRecorder.kt
- `Core Systems Role` --references--> `CameraController`  [INFERRED]
  agents/core-systems.md → app/src/main/java/com/showhow/capture/CameraController.kt
- `Core Systems Role` --references--> `MotionSource`  [INFERRED]
  agents/core-systems.md → app/src/main/java/com/showhow/capture/MotionSource.kt
- `Mode Engine` --shares_data_with--> `MotionSource`  [INFERRED]
  docs/ARCHITECTURE.md → app/src/main/java/com/showhow/capture/MotionSource.kt
- `Gate Knobs` --references--> `AdaptiveGate`  [INFERRED]
  agents/policy-tuning.md → app/src/main/java/com/showhow/core/AdaptiveGate.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **The Four Hard Constraints Of ShowHow** — agents_showhow, agents_no_internet_permission, agents_step_cutter_is_pause_detector_plus_word_list, agents_mode_engine_contains_no_ai, agents_warnings_advise_never_block [EXTRACTED 1.00]
- **Live Policy Retune Under Red Light** — agents_red_light, agents_no_hardcoded_number, agents_policy, agents_policy_store, agents_policy_repository, agents_policy_json_live_retune [EXTRACTED 1.00]
- **Tool-Agnostic Instruction Pointer Set** — agents_agents_md_entry_point, claude_instructions_pointer, _github_copilot_instructions_pointer, agents_readme_role_agents, agents_readme_tool_portability [EXTRACTED 1.00]
- **Show-to-Guide Pipeline** — docs_architecture_show_mode, docs_architecture_recording_data_flow, docs_architecture_adaptive_gate, docs_architecture_step_cutter, docs_architecture_storage_format, docs_architecture_guide_mode [EXTRACTED 1.00]
- **Red Light Config-Only Discipline** — docs_workflow_red_light, docs_workflow_nothing_hardcoded, docs_architecture_policy_hot_reload, docs_workflow_office_kit, docs_workflow_ayaan_memon [INFERRED 0.85]
- **Three-Person Ownership Partition** — docs_workflow_file_ownership, docs_workflow_kshitij_desai, docs_workflow_anushka_unde, docs_workflow_ayaan_memon, showhow_team_guide_1_file_ownership [EXTRACTED 1.00]
- **ShowHow Role Ownership Split** — agents_core_systems_core_systems_role, agents_ai_integration_ai_integration_role, agents_ui_compose_ui_compose_role, agents_policy_tuning_policy_tuning_role, agents_demo_pitch_demo_pitch_role, agents_build_verify_build_verify_role [EXTRACTED 1.00]
- **Offline Guarantee Enforcement** — agents_ai_integration_offline_or_it_does_not_ship, agents_ai_integration_models_never_enter_git, agents_build_verify_merged_manifest_check, agents_build_verify_model_file_commit_check, agents_demo_pitch_no_internet_proof [INFERRED 0.85]
- **Advisory Never Blocking** — agents_core_systems_nothing_blocks, agents_ai_integration_no_veto_from_models, agents_ui_compose_no_disabled_controls, agents_build_verify_nothing_blocks_check, agents_demo_pitch_the_honest_part [INFERRED 0.85]

## Communities (24 total, 1 thin omitted)

### Community 0 - "Project Constraints And Ownership"
Cohesion: 0.07
Nodes (50): Copilot Instructions Pointer, AdaptiveGate, AGENTS.md Entry Point, Anushka, Ayaan, core/ Is Pure Kotlin With Zero android.* Imports, FakeAsr / FakeCaptioner / FakeGestureSource, One File, One Owner (+42 more)

### Community 1 - "Scene Check And Architecture Rationale"
Cohesion: 0.06
Nodes (39): Bitmap, IntArray, RealSceneCheck, Adaptive Gate, CutConfirmer, Fake AI Stack, Guide Mode, LinkWordConfirmer (+31 more)

### Community 2 - "Guide Storage And Navigation"
Cohesion: 0.07
Nodes (15): AndroidViewModel, Guide, Step, GuideStore, Debug, Library, Player, Review (+7 more)

### Community 3 - "AI Interfaces And Fakes"
Cohesion: 0.08
Nodes (23): AiStack, Asr, Captioner, Gesture, FIST, NONE, OPEN_PALM, POINT (+15 more)

### Community 4 - "Compose Screens And UI Rules"
Cohesion: 0.13
Nodes (25): UI Compose Stub, MediaPipe Gesture Recognition, Wiring RealSceneCheck Into ShowScreen, Symptom To Knob Table, Always Show The Reason, Debug Screen Content Is Load-Bearing, Four Player Visual States, Sealed Interface Navigation (+17 more)

### Community 5 - "Policy Store And Reload Tests"
Cohesion: 0.10
Nodes (15): Malformed Policy Is Ignored, StateFlow, PolicyStore, PolicyStoreTest, Dwell Commit Delay, Mode Engine, Mode Reason String, Policy Hot-Reload (+7 more)

### Community 6 - "Step Cutter And Its Tests"
Cohesion: 0.17
Nodes (9): Cutter Knobs, CutConfirmer, LinkWordConfirmer, PassThroughConfirmer, Sample, StepCutter, StepRange, StepCutterTest (+1 more)

### Community 7 - "Adaptive Gate And Motion Source"
Cohesion: 0.12
Nodes (13): Flow, MotionSource, AdaptiveGate, sanitize(), AdaptiveGateTest, run(), Android-Free Core Package, Module Map (+5 more)

### Community 8 - "Mode Engine And Its Tests"
Cohesion: 0.16
Nodes (11): Mode, EASY, HANDS, TALK, TAP, ModeDecision, ModeEngine, ModeInputs (+3 more)

### Community 9 - "Audio Recorder And Model Integration"
Cohesion: 0.13
Nodes (18): AI Integration Stub, AI Integration Role, AiStack Interface Contract, Gemma 3n Captions, Keep The Fakes, Missing Model Degrades To Fake, Models Never On The Main Thread, libc++_shared pickFirsts And arm64-v8a Only (+10 more)

### Community 10 - "Scene Hash Image Similarity"
Cohesion: 0.18
Nodes (7): IntArray, SceneHash, image(), IntArray, rgb(), SceneHashTest, DoubleArray

### Community 11 - "Demo, Pitch And Policy Tuning Roles"
Cohesion: 0.14
Nodes (18): Demo Pitch Stub, Models Never Enter Git, Model File Commit Check, Cutter Stays A Pause Detector Plus Word List, Link Word Confirmation, Mode Engine Stays A Decision Table, Check-In Prerequisites, Demo Pitch Role (+10 more)

### Community 12 - "CameraX Capture Controller"
Cohesion: 0.18
Nodes (6): CameraController, ImageAnalysis, ImageCapture, LifecycleOwner, PreviewView, ProcessCameraProvider

### Community 13 - "Core Laws And Verification"
Cohesion: 0.22
Nodes (11): Core Systems Stub, Models Get No Veto, Android-Free Core Check, Verification Checklist, Twenty-One JVM Tests, Nothing Blocks Check, Behaviour Sentence Test Names, Core Imports Zero Android (+3 more)

### Community 14 - "Policy Tunable Numbers"
Cohesion: 0.31
Nodes (6): Policy Tuning Stub, Tunable Drift Check, No Magic Numbers, Numbers Come From Policy, parse(), Policy

### Community 15 - "Offline Proof And Demo Runbook"
Cohesion: 0.22
Nodes (9): Offline Or It Does Not Ship, Merged Manifest Permission Check, Pre-Demo Checks, Photo To Step Index Mismatch, Break It On Purpose, Demo Runbook, No INTERNET Permission Proof, The Five-Beat Pitch (+1 more)

### Community 16 - "Policy Hot Reload Watcher"
Cohesion: 0.29
Nodes (4): Policy Hot Reload, StateFlow, PolicyRepository, FileObserver

### Community 17 - "App Entry And Theme"
Cohesion: 0.29
Nodes (4): MainActivity, ShowHowTheme(), Bundle, ComponentActivity

### Community 18 - "Build Verify And Red Light"
Cohesion: 0.40
Nodes (5): Build Verify Stub, Build Verify Role, Ranked Finding Report Format, Ponytail Shortcut Comment, Red Light Window

### Community 19 - "Gradle Wrapper Script"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

## Ambiguous Edges - Review These
- `Warnings Advise, They Never Block` → `ShowHowViewModel`  [AMBIGUOUS]
  AGENTS.md · relation: conceptually_related_to
- `mergeShort and maxSteps Cap` → `Demo Runbook and Five Recorded Guides`  [AMBIGUOUS]
  docs/WORKFLOW.md · relation: conceptually_related_to
- `Policy Hot-Reload` → `Conflict Escape Hatch`  [AMBIGUOUS]
  docs/WORKFLOW.md · relation: semantically_similar_to

## Knowledge Gaps
- **30 isolated node(s):** `NONE`, `OPEN_PALM`, `FIST`, `THUMB_UP`, `POINT` (+25 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **1 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `Warnings Advise, They Never Block` and `ShowHowViewModel`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `mergeShort and maxSteps Cap` and `Demo Runbook and Five Recorded Guides`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `Policy Hot-Reload` and `Conflict Escape Hatch`?**
  _Edge tagged AMBIGUOUS (relation: semantically_similar_to) - confidence is low._
- **Why does `Core Systems Role` connect `Core Laws And Verification` to `Guide Storage And Navigation`, `Compose Screens And UI Rules`, `Policy Store And Reload Tests`, `Step Cutter And Its Tests`, `Adaptive Gate And Motion Source`, `Mode Engine And Its Tests`, `Audio Recorder And Model Integration`, `Scene Hash Image Similarity`, `Demo, Pitch And Policy Tuning Roles`, `CameraX Capture Controller`, `Policy Tunable Numbers`, `Offline Proof And Demo Runbook`, `Policy Hot Reload Watcher`, `Build Verify And Red Light`?**
  _High betweenness centrality (0.273) - this node is a cross-community bridge._
- **Why does `ShowHowViewModel` connect `Guide Storage And Navigation` to `Scene Check And Architecture Rationale`, `AI Interfaces And Fakes`, `Compose Screens And UI Rules`, `Policy Store And Reload Tests`, `Audio Recorder And Model Integration`, `CameraX Capture Controller`, `Core Laws And Verification`, `Policy Tunable Numbers`, `Offline Proof And Demo Runbook`?**
  _High betweenness centrality (0.267) - this node is a cross-community bridge._
- **Why does `SceneHash` connect `Scene Hash Image Similarity` to `Scene Check And Architecture Rationale`, `Compose Screens And UI Rules`, `Core Laws And Verification`, `Adaptive Gate And Motion Source`?**
  _High betweenness centrality (0.098) - this node is a cross-community bridge._
- **Are the 4 inferred relationships involving `Core Systems Role` (e.g. with `AudioRecorder` and `CameraController`) actually correct?**
  _`Core Systems Role` has 4 INFERRED edges - model-reasoned connections that need verification._
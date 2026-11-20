# Workflow: android-ui

## Goal
Implement and verify the Android UI: `AthraHomePage`, `MainActivity`, Compose task/event/skill/memory displays, and truthful state rendering.

## Scope
Applies to `app/src/main/java/org/aethra/assistant/AthraHomePage.kt`, `MainActivity.kt`, and Compose UI code. Does not cover core task engine logic or business rules.

## Triggers
- File patterns: `app/src/main/java/org/aethra/assistant/**`
- Intent phrases: "AthraHomePage", "task display", "event display", "skill display", "memory display", "task note", "UI state"

## Inputs
- `AthraHomePage.kt` (task list, event list, skill list, memory list, task creation button)
- `MainActivity.kt` (Runtime creation, start, shutdown, setContent)
- `Runtime` public API (taskEngine, eventBus, memoryAndroid, skillRegistryAndroid, createTask)
- `EventBus.recent()` for event display

## Invariants
- A UI status must never imply a backend tool is working when it is only planned (DESIGN_SYSTEM.md)
- Task rows must display `task.note` so execution failure reasons are visible
- Task state in UI must reflect actual Core task state (not remain at RUNNING after terminal state)
- Events from `runtime.eventBus?.recent(50)` must be real events, not an empty list
- No fake skills, fake memory, fake tool results, or placeholder functionality
- "No skills yet" / "No memories yet" only when genuinely empty

## Procedure
1. Inspect `AthraHomePage.kt` and `MainActivity.kt`
2. Implement the change (minimal, one logical change)
3. Verify the change compiles (CI Experimental APK for Android changes)
4. Confirm UI reflects real Core state (task.note, eventBus.recent, etc.)
5. Confirm review gate
6. Commit `feat(android): ...` or `fix(android): ...`
7. Push; confirm CI Experimental APK green

## Outputs
- Modified Android UI code
- Green CI Experimental APK run
- Clean working tree

## Review gate
- [ ] UI reflects actual Core task state (task.note shown, terminal states not stuck at RUNNING)
- [ ] Events come from `runtime.eventBus?.recent(50)`, not empty list
- [ ] No fake skills, memory, or tool results in UI
- [ ] `:app:assembleDebug` green in CI Experimental APK
- [ ] CI green on `origin/codex/phase-c-handoff`

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `skills/aethra/workflows/android-runtime/SKILL.md` — Runtime API (related)
- `skills/aethra/workflows/core/SKILL.md` — task state (related)
- `docs/ui/DESIGN_SYSTEM.md` — UI design direction

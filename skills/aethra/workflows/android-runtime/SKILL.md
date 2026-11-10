# Workflow: android-runtime

## Goal
Implement and verify the Android runtime glue: `Runtime.kt`, `PlatformAdapters`, `Lifecycle`, `CoreRegistry` wiring, MemoryAndroid, and Android task runner integration.

## Scope
Applies to `app/src/main/java/**/runtime/`, `app/src/main/java/**/adapters/PlatformAdapters.kt`, `MemoryAndroid`, and the binding between Android app and AETHRA Core. Does not cover UI composables (that is `android-ui`) or core task engine logic (that is `core`).

## Triggers
- File patterns: `app/src/main/java/**/runtime/**`, `app/src/main/java/**/adapters/PlatformAdapters.kt`, `app/src/main/java/**/memory/MemoryAndroid.kt`
- Intent phrases: "Runtime.start", "bindCoreTaskEngine", "PlatformAdapters", "LifecycleImpl", "taskRunner injection", "MemoryAndroid.initialize"

## Inputs
- `Runtime.kt` (start, shutdown, createTask, eventBus, memoryAndroid)
- `PlatformAdapters.kt` (taskRunner, coreTaskEngine binding, lifecycle provider, registerAndroidTools)
- `LifecycleImpl.kt` (start, stop, taskEngineProvider)
- `CoreRegistry.kt` (taskRunner parameter, taskEngine)
- `MemoryAndroid.kt` (initialize, add, addTaskResult, search, all)
- `Runtime.kt` imports and symbol references

## Invariants
- `Runtime.start()` runs inside `runBlocking`; suspend calls are legal there
- `PlatformAdapters.bindCoreTaskEngine()` must be called before `lifecycle.start()`
- `LifecycleImpl` uses `taskEngineProvider()` to get the Core-owned TaskEngine
- `ProviderTaskRunner` must be imported in `PlatformAdapters.kt` (unresolved reference breaks `:app:assembleDebug`)
- `memoryAndroid.initialize()` is called after binding
- All core symbols referenced in Runtime/PlatformAdapters must exist in core

## Procedure
1. Inspect Runtime.kt, PlatformAdapters.kt, LifecycleImpl.kt
2. Verify all referenced symbols exist in core (TaskEngine.create, TaskRunner, ProviderTaskRunner, etc.)
3. Implement the change (minimal, one logical change)
4. Confirm `:core:test --rerun` passes locally (core unchanged but verify)
5. For Android changes, confirm CI Experimental APK (`:app:assembleDebug`) is green
6. Confirm review gate
7. Commit `fix(android): ...` or `feat(android): ...`
8. Push; confirm CI green

## Outputs
- Modified Android runtime code
- Green CI Experimental APK run
- Clean working tree

## Review gate
- [ ] All core symbols referenced in app code exist and compile
- [ ] `ProviderTaskRunner` is imported where used
- [ ] `bindCoreTaskEngine()` is called before `lifecycle.start()`
- [ ] `Runtime.start()` is inside `runBlocking`
- [ ] `:app:assembleDebug` green in CI Experimental APK run
- [ ] CI green on `origin/codex/phase-c-handoff`

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `skills/aethra/workflows/core/SKILL.md` — TaskEngine (related)
- `skills/aethra/workflows/tools/SKILL.md` — Android tool dispatch (related)
- `skills/aethra/workflows/memory/SKILL.md` — MemoryEngine (related)

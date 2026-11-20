# Workflow: core

## Goal
Implement and verify changes to AETHRA Core's task engine: `TaskEngine`, task state transitions, execution lifecycle, claim/retry mechanics, and terminal-state persistence.

## Scope
Applies to `core/src/main/kotlin/org/aethra/core/task/` (TaskEngine, Task, TaskState, TaskPersistence, TaskScheduler, ProviderTaskRunner) and related core domain files. Does not cover Android app code, provider logic, or UI.

## Triggers
- File patterns: `core/src/main/kotlin/org/aethra/core/task/**`
- Intent phrases: "task engine", "start task", "cancel task", "resume", "retry", "claim execution", "terminal state", "persistence", "task state machine"

## Inputs
- Current `TaskEngine.kt`, `Task.kt`, `TaskState.kt`, `TaskPersistence.kt`, `TaskScheduler.kt`
- Relevant contract tests under `core/src/test/kotlin/org/aethra/core/task/`
- `docs/security/EXECUTION_MODEL.md` (task execution and recovery section)

## Invariants
- A cancelled task must never execute work outside the approval chain
- Terminal states (COMPLETED, FAILED, CANCELLED) must be persisted immediately
- Exactly one coroutine may own a task's execution cycle (atomic claim)
- `run()` must report RUNNING at initiation, not the eventual terminal state
- No test may be weakened, skipped, or deleted to make CI green

## Procedure
1. Inspect the current `TaskEngine.kt` and related task files
2. Identify the specific defect or gap (race condition, missing persist, state transition, etc.)
3. Implement the minimal fix — one logical change per commit
4. Add or update a deterministic contract test covering the change
5. Run `:core:test --rerun` locally; all tests must pass
6. Confirm the review gate below passes
7. Commit with message `fix(core): ...` or `feat(core): ...`
8. Push to `codex/phase-c-handoff`; confirm CI Core Contract Tests green

## Outputs
- Modified `TaskEngine.kt` or related task file(s)
- New or updated contract test(s)
- Green CI Core Contract Tests run
- Clean working tree

## Review gate
- [ ] `:core:test --rerun` passes locally with 0 failures
- [ ] Terminal states are persisted (COMPLETED, FAILED, CANCELLED)
- [ ] Atomic claim preserved (exactly one execution cycle owner)
- [ ] No tests removed or weakened
- [ ] Commit is focused and atomic
- [ ] CI Core Contract Tests green on `origin/codex/phase-c-handoff`

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `skills/aethra/workflows/tasks/SKILL.md` — task lifecycle (related)
- `skills/aethra/workflows/dependencies/SKILL.md` — task dependencies (related)
- `docs/security/EXECUTION_MODEL.md` — task execution and recovery

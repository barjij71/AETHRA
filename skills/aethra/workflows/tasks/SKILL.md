# Workflow: tasks

## Goal
Implement and verify task creation, scheduling, enqueue/drain logic, and lifecycle management in AETHRA Core.

## Scope
Applies to `Task.create()`, `TaskEngine.enqueue()`, `TaskEngine.drainQueue()`, `TaskEngine.run()`, task state transitions (QUEUED → PREPARING → APPROVED → RUNNING), and task registration. Does not cover provider execution or Android UI.

## Triggers
- File patterns: `core/src/main/kotlin/org/aethra/core/task/TaskEngine.kt`, `core/src/main/kotlin/org/aethra/core/task/Task.kt`
- Intent phrases: "create task", "enqueue", "drain queue", "schedule", "task lifecycle", "start task"

## Inputs
- `TaskEngine.kt` (create, enqueue, drainQueue, run methods)
- `Task.kt` (state, claimExecution, complete, fail, cancel, resetForRetry)
- `TaskState.kt` (state machine transitions)
- Contract tests under `core/src/test/kotlin/org/aethra/core/task/`

## Invariants
- Tasks with dependencies are not auto-scheduled; they wait in WAITING_DEPENDENCY
- `enqueue()` only adds tasks in QUEUED state to pending
- `run()` is idempotent — a second call on RUNNING/APPROVED returns current state
- Sensitive risk class tasks enter WAITING_APPROVAL, not RUNNING
- maxConcurrent gates the number of simultaneously running tasks

## Procedure
1. Inspect `TaskEngine.kt` create/enqueue/drainQueue/run
2. Implement the change (minimal, one logical change)
3. Add or update a contract test for the changed behavior
4. Run `:core:test --rerun` locally; all tests pass
5. Confirm review gate
6. Commit `fix(core): ...` or `feat(core): ...`
7. Push; confirm CI green

## Outputs
- Modified task engine or task file(s)
- New or updated contract test(s)
- Green CI Core Contract Tests

## Review gate
- [ ] `:core:test --rerun` passes locally
- [ ] Dependency tasks are not auto-scheduled
- [ ] run() idempotency preserved
- [ ] maxConcurrent gating intact
- [ ] No tests weakened or removed
- [ ] CI green on `origin/codex/phase-c-handoff`

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `skills/aethra/workflows/core/SKILL.md` — core task engine (related)
- `skills/aethra/workflows/dependencies/SKILL.md` — dependencies (related)

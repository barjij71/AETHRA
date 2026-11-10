# Workflow: dependencies

## Goal
Implement and verify task dependency chains, wake-up logic, and approval gates on dependency wake-up in AETHRA Core.

## Scope
Applies to dependency registration, `wakeReadyDependents()`, `wakeTask()`, `onTaskTerminal()`, dependency state transitions (WAITING_DEPENDENCY → APPROVED → RUNNING), and approval-on-wake-up for sensitive tasks. Does not cover provider execution or UI.

## Triggers
- File patterns: `core/src/main/kotlin/org/aethra/core/task/TaskEngine.kt` (dependency-related methods)
- Intent phrases: "dependsOn", "dependency chain", "wake dependent", "approval on dependency wake-up", "blocked until dependency completes"

## Inputs
- `TaskEngine.kt` (registerDependents, wakeReadyDependents, wakeTask, onTaskTerminal)
- `Task.kt` (dependsOn, state transitions)
- `TaskScheduler.kt` (isReady, wouldCreateCycle)
- Contract tests: `TaskDependencySchedulerContractTest.kt`, `TaskDependencyPropagationContractTest.kt`

## Invariants
- A dependency must be found in the task map at creation time ("dependency not found")
- Dependency cycles are rejected (`wouldCreateCycle`)
- Dependents remain WAITING_DEPENDENCY until all dependencies complete
- A failed dependency leaves its dependent blocked (WAITING_DEPENDENCY), not failed or executed
- A cancelled dependency leaves its dependent blocked, not running
- Sensitive dependents enter WAITING_APPROVAL on dependency wake-up, not RUNNING
- `TaskEngine.create()` rejects missing dependencies before any state change

## Procedure
1. Inspect dependency-related code in `TaskEngine.kt` and `TaskScheduler.kt`
2. Implement the change (minimal, one logical change)
3. Add or update a contract test for dependency behavior
4. Run `:core:test --rerun` locally; all tests pass
5. Confirm review gate
6. Commit `fix(core): ...` or `feat(core): ...`
7. Push; confirm CI green

## Outputs
- Modified dependency-related code
- New or updated contract test(s)
- Green CI Core Contract Tests

## Review gate
- [ ] `:core:test --rerun` passes locally
- [ ] Dependency cycles are detected and rejected
- [ ] Failed/cancelled dependencies block dependents (not execute)
- [ ] Sensitive dependents enter WAITING_APPROVAL on wake-up
- [ ] No tests weakened or removed
- [ ] CI green on `origin/codex/phase-c-handoff`

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `skills/aethra/workflows/core/SKILL.md` — core task engine (related)
- `skills/aethra/workflows/tasks/SKILL.md` — task lifecycle (related)
- `skills/aethra/workflows/policy/SKILL.md` — approval gates (related)

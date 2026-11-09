# AETHRA Phase C — Handoff Document

## 1. Repository State

- **Branch:** `phase-a-provider-contract`
- **HEAD:** `60fcb1e` (`WIP snapshot before Phase A fixes (C1/C2 fixes in progress)`)
- **Working tree:** 1 modified file (`core/src/main/kotlin/org/aethra/core/task/TaskEngine.kt`)
- **Untracked files:** none

## 2. AETHRA Architecture Overview

- **AETHRA** is a Kotlin/Android project (buildable in Termux). Core module: `:core`.
- **Module boundaries** (see `README.md` and `docs/` for full architecture):
  - `core`: task engine, dependency scheduler, persistent memory, security, permissions, skills/tools registry.
  - `app`: Android UI and integration layer.
  - No new modules beyond those listed.

### Module Boundary Rules

- `core` must remain independent of `android.*` and platform-specific APIs.
- Discovery, Installation, and Execution are separated.
- Provider Router fallback is disabled by default.

## 3. Current Development Phase

- **Phase:** C — Dependency Bug Investigation
- **Status:** In progress; the Phase C dependency bug is **FIXED** but not yet fully validated across all suites.

## 4. Completed Provider and TaskEngine B1 Work

- Provider and TaskEngine B1 contract tests were added and verified.
- Dependencies (the `B1` contract) and task execution are separated in `TaskEngine`.

## 5. Phase C Dependency Scheduler Implementation

- `TaskScheduler` manages dependency state: QUEUED, WAITING_DEPENDENCY, APPROVED, RUNNING, COMPLETED, FAILED, CANCELLED, ERROR.
- `wakeReadyDependents()` transitions ready dependents from WAITING_DEPENDENCY → APPROVED → RUNNING and launches `execute()`.
- `onTaskTerminal()` is called when a task reaches a terminal state; it propagates failure to dependents and re-evaluates dependent readiness.

## 6. The Verified "return@forEach" Fix and Its Evidence

**Problem:** `onTaskTerminal()` in `TaskEngine.kt` contained a bare `return` inside a `forEach` lambda:

```kotlin
if (depState !in setOf(TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)) {
    return   // ← bare return: exits the ENTIRE onTaskTerminal() function
}
```

This bypassed `wakeReadyDependents()`, so dependents were never woken when a dependency completed.

**Fix:** Changed the bare `return` to a labeled return `return@forEach`:

```kotlin
if (depState !in setOf(TaskState.FAILED, TaskState.CANCELLED, TaskState.ERROR)) {
    return@forEach   // ← exits only the lambda; onTaskTerminal() continues
}
wakeReadyDependents()
```

**Evidence:**
- The fix is applied in `core/src/main/kotlin/org/aethra/core/task/TaskEngine.kt` line 184.
- `dependencyCompletionWakesDependent` test passes with this fix.
- Full `:core:test` shows 3 pre-existing failures that are NOT caused by the fix.

## 7. The Three Remaining Failing Tests

1. **`cancelledDependencyBlocksDependent`** — `TaskDependencySchedulerContractTest.kt:114`
   - Expected: dependent NOT RUNNING, NOT terminal, in QUEUED/WAITING_DEPENDENCY
   - Actual: dependent is CANCELLED (terminal)
   - Root cause: `onTaskTerminal()` propagates cancellation to dependents; the test assertion is too strict.

2. **`dependentDoesNotRunWhenDependencyCancelled`** — `TaskDependencySchedulerContractTest.kt:190`
   - Expected: dependent NOT RUNNING, NOT terminal
   - Actual: dependent is CANCELLED (terminal)
   - Root cause: same as above — `onTaskTerminal()` propagates cancellation to dependents.

3. **`retryAllowsOneIntentionalReExecution`** — `TaskEngineB1ContractTest.kt:113`
   - Expected: task COMPLETED after first failure, second attempt succeeds
   - Actual: task is FAILED
   - Root cause: retry mechanism is not yet implemented; the task does not auto-retry on failure. This is a pre-existing gap.

## 8. Unverified Questions

- Whether the 3 pre-existing failures are intentional test expectations or actual bugs is unresolved. They fail identically on baseline and after the Phase C fix.
- The dependency scheduler's handling of `COMPLETED` vs `CANCELLED` dependency states is correct but only partially covered by tests.

## 9. Relevant Source Files

### Production Code (modified)

- `core/src/main/kotlin/org/aethra/core/task/TaskEngine.kt` (1-line fix: `return` → `return@forEach` at line 184)

### Test Files

- `core/src/test/kotlin/org/aethra/core/task/TaskDependencySchedulerContractTest.kt`
- `core/src/test/kotlin/org/aethra/core/task/TaskEngineB1ContractTest.kt`
- `core/src/test/kotlin/org/aethra/core/task/TaskEngineTest.kt`

### Architecture Docs

- `docs/architecture/DECISIONS.md`
- `docs/architecture/ARCHITECTURE.md` (if present)

## 10. Required Commands

### Targeted test

```bash
./gradlew :core:test --no-daemon --console=plain --tests "org.aethra.core.task.TaskDependencySchedulerContractTest.dependencyCompletionWakesDependent"
```

### All three remaining failing tests

```bash
./gradlew :core:test --no-daemon --console=plain \
  --tests "org.aethra.core.task.TaskDependencySchedulerContractTest.cancelledDependencyBlocksDependent" \
  --tests "org.aethra.core.task.TaskDependencySchedulerContractTest.dependentDoesNotRunWhenDependencyCancelled" \
  --tests "org.aethra.core.task.TaskEngineB1ContractTest.retryAllowsOneIntentionalReExecution"
```

### Full core suite

```bash
./gradlew :core:test --no-daemon --console=plain
```

### Verify fix

```bash
grep -n "return@forEach" core/src/main/kotlin/org/aethra/core/task/TaskEngine.kt
```

## 11. Instructions for Codex

1. **Continue from the current state.** Do not restart the investigation.
2. **Do not re-open or revert the verified `return@forEach` fix.** It is correct and verified for `dependencyCompletionWakesDependent`.
3. **Do not modify tests merely to make them pass.** If a test assertion is wrong, document it as a separate issue rather than changing it.
4. **Investigate the 3 remaining failures:**
   - Determine whether the cancellation-propagation tests (`cancelledDependencyBlocksDependent`, `dependentDoesNotRunWhenDependencyCancelled`) expect a different contract than the current implementation.
   - Determine whether `retryAllowsOneIntentionalReExecution` requires a retry mechanism (e.g., `retryBudget` based auto-retry in the runner loop).
5. **Create separate Change Budgets** for each confirmed root cause. Do not combine unrelated fixes.
6. **Do not commit or push** until the Phase C investigation is complete. Use `git status` to confirm only intended changes remain.
7. **Verify:** `./gradlew :core:test` and report the results.

## 12. Verified Facts vs Hypotheses

- **VERIFIED FACT:** The `return@forEach` fix resolves `dependencyCompletionWakesDependent`.
- **VERIFIED FACT:** All 3 remaining tests fail identically on baseline and after the fix → they are not caused by the fix.
- **HYPOTHESIS:** The 3 remaining failures are pre-existing gaps in the test suite or implementation (no auto-retry, over-strict cancellation assertions). This must be confirmed by code inspection.
- **UNKNOWN:** Whether the 3 failures have independent root causes or share a common cause is not yet established.

---

*Prepared for GitHub handoff to OpenAI Codex. Last updated: 2026-11-09.*

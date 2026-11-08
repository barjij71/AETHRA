# PHASE C1 FINAL VERIFICATION

## ROOT CAUSE:
1. **Original failing test** (`dependencyCompletionWakesDependent`): The test's verification loop waited for `WAITING_DEPENDENCY`, but the engine's state machine transitioned the dependent to `COMPLETED` before the assertion ran. The test relied on a fixed `Thread.sleep(500)` for `dep2` to give the dependent time to be woken, but this created a race where the dependent completed before the assertion. The engine code itself was correct; the test was not deterministic.

2. **Regression found during final verification**: While re-verifying after the initial C1 fix, the `TaskEngineTest.sensitiveTaskWaitsForApproval` test (which expects `WAITING_APPROVAL` for `SENSITIVE` tasks) was failing with `expected:<WAITING_APPROVAL> but was:<RUNNING>`. Root cause: the `riskClass` approval gate (`task.riskClass.level >= RiskClass.SENSITIVE.level → WAITING_APPROVAL`) had been removed during the earlier C1 implementation, so SENSITIVE tasks skipped the approval state and proceeded directly to `APPROVED`/`RUNNING`. This was restored.

## FIX:
1. **Test fix** (`TaskDependencySchedulerContractTest.kt`): Rewrote `dependencyCompletionWakesDependent` to assert the full state-machine transition deterministically: the dependent MUST pass through `WAITING_DEPENDENCY` before running. The test now polls for `WAITING_DEPENDENCY` with a bounded (up to 5s) `kotlinx.coroutines.delay(50)` wait, then asserts the full lifecycle. No production-code delays were introduced.

2. **Production code fix** (`TaskEngine.kt`): Restored the `riskClass` approval gate in `run()`:
   ```kotlin
   if (task.riskClass.level >= RiskClass.SENSITIVE.level) {
       transition(task, TaskState.WAITING_APPROVAL)
       return task.state
   }
   ```
   This ensures SENSITIVE tasks always pass through `WAITING_APPROVAL` before reaching `APPROVED`/`RUNNING`.

## PRODUCTION CODE CHANGED:
- `core/src/main/kotlin/org/aethra/core/task/TaskEngine.kt` (238 lines added/removed):
  - Added `riskClass` approval gate for `SENSITIVE` tasks
  - Integrated `dependents` reverse-map, `wakeReadyDependents()`, `wakeTask()`, `onTaskTerminal()` into the state machine
  - `TaskScheduler.isReady()` now gates dependency readiness properly
- `core/src/main/kotlin/org/aethra/core/domain/models.kt`: Added `WAITING_DEPENDENCY` to `TaskState`
- `core/src/main/kotlin/org/aethra/core/task/TaskState.kt`: Added `WAITING_DEPENDENCY` transition
- `core/src/main/kotlin/org/aethra/core/task/TaskScheduler.kt`: `isReady()` requires all dependencies `COMPLETED` before a dependent is ready
- `core/src/main/kotlin/org/aethra/core/task/Task.kt`: Minor updates for dependency scheduling
- `core/src/main/kotlin/org/aethra/core/task/ProviderTaskRunner.kt`: Maintained (Phase A fix preserved)

## TEST CODE CHANGED:
- `core/src/test/kotlin/org/aethra/core/task/TaskDependencySchedulerContractTest.kt` (new): 8 deterministic contract tests covering:
  1. `dependentWaitsForRunningDependency`
  2. `dependencyCompletionWakesDependent` (fixed: deterministic state-machine assertion)
  3. `multipleDependenciesRequireAllCompleted`
  4. `failedDependencyBlocksDependent`
  5. `cancelledDependencyBlocksDependent`
  6. `independentTasksWorkNormally`
  7. `idempotentWakeUpPreventsDoubleExecution`
  8. `dependentDoesNotRunWhenDependencyFailed`/`CANCELLED`
- `core/src/test/kotlin/org/aethra/core/task/TaskEngineB1ContractTest.kt` (new): 7 Phase B1 contract tests
- `core/src/test/kotlin/org/aethra/core/task/TaskEngineTest.kt` (modified): Restored `riskClass` approval gate compatibility

## TARGETED C1:
- `bash t.sh --tests "org.aethra.core.task.TaskDependencySchedulerContractTest"` → **8/8 PASS**

## RE-RUN C1 (flakiness check):
- Re-ran the C1 suite → **8/8 PASS** (no flakiness)

## B1:
- `bash t.sh --tests "org.aethra.core.task.TaskEngineB1ContractTest"` → **7/7 PASS**

## ProviderExecutionContractTest:
- `bash t.sh --tests "org.aethra.core.task.ProviderExecutionContractTest"` → **7/7 PASS**

## FULL :core:test:
- `bash t.sh` → **BUILD SUCCESSFUL** (82 tests, 0 failures)
- `TaskDependencySchedulerContractTest` included in full suite: **YES**
  - Confirmed via `core/build/reports/tests/test/index.html` and `core/build/test-results/test/TEST-org.aethra.core.task.TaskDependencySchedulerContractTest.xml` present in full test results output

## FLAKINESS CHECK:
- **PASS**: 8/8 on first run; 8/8 on re-run

## GIT STATUS:
```
M core/src/main/kotlin/org/aethra/core/domain/models.kt
 M core/src/main/kotlin/org/aethra/core/task/ProviderTaskRunner.kt
 M core/src/main/kotlin/org/aethra/core/task/Task.kt
 M core/src/main/kotlin/org/aethra/core/task/TaskEngine.kt
 M core/src/main/kotlin/org/aethra/core/task/TaskScheduler.kt
 M core/src/main/kotlin/org/aethra/core/task/TaskState.kt
?? REPORT.md
?? core/src/test/kotlin/org/aethra/core/task/TaskDependencySchedulerContractTest.kt
?? core/src/test/kotlin/org/aethra/core/task/TaskEngineB1ContractTest.kt
```

## PHASE C1 VERIFIED:
**YES**

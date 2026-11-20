# AETHRA Step 4 Design Document

## Overview

This document describes the design for Step 4: Workflow Engine Finalization, building on the W1 foundation.

## Architecture Principles

1. **WorkflowEngine is a coordinator only** - delegates all task execution to TaskEngine
2. **TaskEngine remains sole execution authority** - no bypassing TaskEngine
3. **Preserve all W1 invariants** - durable intent, recovery, ownership, etc.
3. **Deterministic behavior** - all scheduling, concurrency, cancellation must be deterministic

## Data Model Extensions

### WorkflowNode Extensions
```kotlin
data class WorkflowNode(
    val id: String,
    val taskId: String,
    val goal: String,
    val role: String = "assistant",
    val riskClass: RiskClass = RiskClass.READ_ONLY,
    val dependsOn: List<String> = emptyList(),
    var state: TaskState = TaskState.QUEUED,
    // NEW: Failure policy per node
    val failurePolicy: FailurePolicy = FailurePolicy.BLOCK_DESCENDANTS,
    // NEW: Retry configuration
    val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT
)

enum class FailurePolicy {
    BLOCK_DESCENDANTS,  // Default: failed dependency blocks descendants
    ALLOW_CONTINUE      // Depends on workflow-level continueOnFailure
}

data class RetryPolicy(
    val maxRetries: Int = 0,
    val backoffMs: Long = 1000,
    val maxBackoffMs: Long = 30_000
)
```

### Workflow Extensions
```kotlin
data class Workflow(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val nodes: List<WorkflowNode>,
    var state: TaskState = TaskState.QUEUED,
    val createdAt: Long = System.currentTimeMillis(),
    var completedAt: Long? = null,
    var attemptCounter: Int = 0,
    var executionAttemptId: String? = null,
    // NEW: Workflow-level concurrency
    val maxConcurrentTasks: Int = 4,
    // NEW: Failure policies
    val failurePolicy: WorkflowFailurePolicy = WorkflowFailurePolicy.FAIL_FAST,
    // NEW: Recovery configuration
    val recoveryPolicy: RecoveryPolicy = RecoveryPolicy.DEFAULT
)

enum class WorkflowFailurePolicy {
    FAIL_FAST,      // Stop on first failure, cancel all
    CONTINUE_ON_FAILURE  // Continue independent branches, block descendants
}

data class RecoveryPolicy(
    val maxRecoveryAttempts: Int = 3,
    val recoveryTimeoutMs: Long = 30_000
)
```

## Phase 1: DAG Validation

### Implementation Plan
1. **Enhance `validateWorkflowGraph()`** in WorkflowEngine
   - Add workflow ID to error messages
   - Add validation for `maxConcurrentTasks` (must be >= 1)
   - Add validation for failure policy configuration
   - Return structured `ValidationResult` instead of throwing exceptions

2. **ValidationResult Structure**
```kotlin
sealed class ValidationResult {
    data class Valid(val workflow: Workflow) : ValidationResult()
    data class Invalid(
        val workflowId: String,
        val errors: List<ValidationError>
    ) : ValidationResult()
}

data class ValidationError(
    val category: ValidationCategory,
    val message: String,
    val affectedNodeIds: List<String> = emptyList(),
    val invalidDependencyEdge: Pair<String, String>? = null
)

enum class ValidationCategory {
    DUPLICATE_NODE_ID,
    DUPLICATE_TASK_ID,
    SELF_DEPENDENCY,
    MISSING_DEPENDENCY,
    SELF_CYCLE,
    TWO_NODE_CYCLE,
    LONGER_CYCLE,
    DISCONNECTED_COMPONENT_CYCLE,
    MULTIPLE_CYCLES,
    INVALID_CONCURRENCY,
    INVALID_FAILURE_POLICY,
    INVALID_RETRY_POLICY
}
```

3. **Enhance `createWorkflow()`** to return `ValidationResult` and reject invalid workflows before persistence

### Tests to Add
- Valid linear DAG
- Valid branching DAG
- Valid disconnected DAG
- Missing dependency
- Self-cycle
- Two-node cycle
- Longer cycle
- Cycle in disconnected component
- Multiple independent cycles
- Duplicate task IDs
- Duplicate dependency edges
- Invalid concurrency value (0 or negative)
- Invalid failure policy configuration

## Phase 2: Topological Scheduling

### Implementation Plan
1. **Enhance `execute()` to use deterministic topological scheduling**
   - Use `topologicalSort()` result for scheduling order
   - Document tie-breaking rule: original declaration order for same-level nodes
   - Track eligibility explicitly

2. **Scheduling State Machine**
   - Track task eligibility explicitly in workflow state
   - Tasks become eligible when all dependencies are COMPLETED
   - Use stable tie-breaking: original declaration order

2. **Coordination with TaskEngine**
   - WorkflowEngine manages eligibility
   - TaskEngine manages execution readiness (maxConcurrent, approval gates)
   - Release tasks in topological order when eligible

## Phase 3: Max Concurrency

### Implementation Plan
1. **Add workflow-level concurrency control**
   - Add `Semaphore(maxConcurrentTasks)` to WorkflowEngine per workflow
   - Acquire permit before releasing task to TaskEngine
   - Release permit on task completion/failure/cancellation

2. **Workflow-level concurrency state**
   - Track active task count per workflow
   - Queue eligible tasks when limit reached
   - Release permits on task completion/failure/cancellation

3. **Integration with TaskEngine**
   - TaskEngine manages its own `maxConcurrent` for actual execution
   - WorkflowEngine manages workflow-level concurrency
   - Both limits enforced: workflow limit AND TaskEngine limit

## Phase 4: Cancellation Propagation

### Implementation Plan
1. **WorkflowEngine.cancel()** enhancements
   - Acquire workflow lock
   - Cancel all active tasks via TaskEngine.cancel()
   - Cancel queued/pending tasks
   - Handle races with task completion
   - Ensure no orphaned tasks

2. **Cancellation Semantics**
   - Cancel workflow → cancel all active tasks
   - Cancelled task → block dependents (per existing W1 behavior)
   - Independent tasks unaffected
   - Cancellation idempotent
   - Cancellation during persistence handled gracefully

## Phase 5: Failure Policies

### Implementation Plan
1. **Add failure policy fields to Workflow**
   ```kotlin
   enum class WorkflowFailurePolicy {
       FAIL_FAST,
       CONTINUE_ON_FAILURE
   }
   ```

2. **Fail-Fast Behavior**
   - On first task failure: trigger workflow stop
   - Cancel all active tasks
   - Block new task releases
   - Set workflow state to FAILED

3. **Continue-on-Failure Behavior**
   - Failed task recorded
   - Descendants of failed task blocked
   - Independent branches continue
   - Final workflow status reflects failures

## Phase 6: Recovery Idempotency

### Implementation Plan
1. **Ensure idempotent recovery**
   - Multiple recoveries produce same result
   - executionAttemptId preserved
   - attemptCounter not incremented during recovery
   - No duplicate tasks created

2. **Recovery Validation**
   - Validate taskIdMap consistency
   - Verify executionAttemptId matches
   - Reject recovery if inconsistent

## Phase 7: Persistence and Event Ordering

### Implementation Plan
1. **State transition ordering**
   - Calculate new state
   - Persist state
   - Publish event AFTER successful persistence

2. **New Events**
   - workflow_validation_completed/failed
   - workflow_scheduling_started
   - task_became_eligible
   - task_blocked_by_dependency
   - task_execution_started
   - task_completed/failed/cancelled
   - workflow_fail_fast_activated
   - workflow_completed/failed/cancelled
   - workflow_recovery_completed/failed

## Phase 8: Testing Strategy

### Test-First Approach
For each phase:
1. Add focused contract tests first
2. Implement minimal change
2. Run focused tests
3. Run related package tests
4. Inspect regressions
5. Proceed when stable

## Implementation Order

1. **Phase 1: DAG Validation** (Week 1)
   - Enhance validation
   - Add structured validation errors
   - Add comprehensive tests

2. **Phase 2: Topological Scheduling** (Week 1-2)
   - Deterministic scheduling
   - Explicit eligibility tracking

3. **Phase 3: Max Concurrency** (Week 2)
   - Workflow-level semaphore
   - Permit management

4. **Phase 4: Cancellation** (Week 2)
   - Complete cancellation propagation
   - Race condition handling

4. **Phase 5: Failure Policies** (Week 2-3)
   - continueOnFailure / failFast
   - Policy precedence

5. **Phase 6: Recovery** (Week 3)
   - Idempotency tests
   - Edge case coverage

6. **Phase 7: Persistence/Events** (Week 3)
   - Event ordering
   - New event types

7. **Phase 8: Integration Tests** (Week 3-4)
   - End-to-end tests
   - Regression tests

## W1 Constraints Checklist

- [ ] Durable intent before task creation
- [ ] ExecutionAttemptId uniqueness
- [ ] Recovery after intent before first Task
- [ ] Safe shutdown drain
- [ ] Corrupt persistence handling
- [ ] Schema/version compatibility
- [ ] Storage identity ownership
- [ ] OUTCOME_UNKNOWN semantics
- [ ] Safe shutdown drain
- [ ] Corruption detection
- [ ] Storage identity ownership

## Risk Mitigation

1. **Concurrency bugs** - Use deterministic test synchronization, avoid sleeps
2. **Recovery edge cases** - Test all recovery scenarios explicitly
3. **Cancellation races** - Use proper synchronization primitives
4. **Performance** - Profile before/after, avoid unnecessary synchronization
5. **W1 regression** - Run full W1 test suite after each phase

## Next Steps

1. Start Phase 1: DAG Validation
2. Implement enhanced validation with structured errors
2. Add comprehensive tests
3. Run W1 test suite to verify no regressions
4. Proceed to Phase 2
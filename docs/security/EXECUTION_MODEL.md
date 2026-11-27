# AETHRA Security and Execution Model

**Status:** implementation-backed security baseline. Individual controls must remain covered by regression tests as the code evolves.

## Trust boundaries

1. Model/agent output is untrusted input.
2. Core policy and Task execution boundaries enforce task scope, capability availability, risk classification, authorization, verification, and persistence.
3. Android platform operations are exposed through the Android runtime/task-runner boundary.
4. Downloaded skills, external tools, repositories, and generated code are untrusted inputs until checked.
5. External tool output is evidence/input, not authoritative Task completion.

## Execution ordering

For tool-oriented operations:

````

TaskEngine
  → TaskRunner
  → ToolRegistry
  → CapabilityAvailability
  → ExecutionPolicy
  → PermissionBroker when required
  → Tool operation
  → TaskVerifier
  → persistence
````


The exact runner may vary by platform. A platform runner must not bypass the authoritative TaskEngine, policy, permission, verification, or persistence boundaries.

## Permission and risk

Capability availability answers whether the environment can perform an operation. Permission answers whether the required user authorization has been granted. They are distinct.

Execution mode may change interactive approval behavior where explicitly supported by the runner, but FULL_ACCESS does not bypass capability checks, ExecutionPolicy, TaskEngine authority, TaskVerifier, persistence, or other applicable authorization requirements.

## Skill lifecycle

1. Acquire only from configured trusted sources.
2. Record origin, version, integrity, license, dependencies, requested capabilities, and install size.
3. Inspect manifests/files and test in a constrained environment when possible.
4. Require user approval before first execution of a newly installed skill.
5. Enforce least privilege at runtime.
6. Record activity and support disable/uninstall/rollback where implemented.

## Agent lifecycle

Agents may be composed from existing roles, skills, tools, and permissions. Generated code must be isolated, inspected, tested, reviewed, and explicitly approved before persistent activation.

Agents may request capabilities but may not grant themselves capabilities.

## Task execution and recovery

Task state includes execution-attempt information, retry state, terminal state, verification evidence, and persistence. On resume, inspect actual state before repeating side effects. Apply retry budgets, cancellation, timeouts, and resource limits.

## Linux and Root

Linux/root integration is a future platform boundary unless the current repository contains a concrete implementation. A future root bridge must be narrow, capability-mediated, auditable, and independently tested. A chroot alone must not be treated as a complete security sandbox.

## Privacy and secrets

Keep API keys, tokens, and private credentials out of source control, logs, prompts, and ordinary memory. Use protected platform storage where applicable.

## Reporting

Reports must distinguish observed facts, test results, inferences, unknowns, and proposed changes.

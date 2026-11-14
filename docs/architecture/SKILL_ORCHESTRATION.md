# AETHRA Skill Collaboration and Orchestration Architecture

**Status:** Proposed architecture decision  
**Scope:** Independent AETHRA Core; Android-first  
**Reference pattern:** Jakeschincariol/trader-skill (MIT), adapted rather than embedded  
**Non-goal:** Requiring Claude, Hermes, a specific model provider, or a trading-specific workflow.

## 1. Decision

AETHRA will coordinate skills through a Core-owned orchestration layer. Skills remain independently discoverable and executable, but they collaborate through explicit task contracts, typed artifacts, dependency graphs, events, and policy gates.

The orchestrator—not an LLM prompt and not an individual skill—owns workflow state, scheduling, retries, cancellation, dependency resolution, permission checks, and final completion.

External skill formats (including `SKILL.md`) may be imported through adapters. They do not become the internal runtime contract and cannot grant themselves permissions.

## 2. Concepts and responsibilities

- **Skill:** A versioned capability package: manifest, instructions, input/output schemas, tool references, tests, and declared permissions.
- **Agent:** A runtime role/persona that reasons over a task and may invoke approved skills. An agent is not automatically a separate model or process.
- **Task:** A user goal represented as a durable, traceable execution record.
- **Workflow:** A directed acyclic graph (DAG) of task steps, dependencies, conditions, and approval gates. Cycles are rejected in the initial version.
- **Artifact:** A typed, versioned result produced by a step. Downstream steps consume artifact references, not hidden chat history.
- **Tool:** A bounded operation exposed to skills through Core-controlled adapters.
- **Policy Engine:** The authority that decides whether a skill/tool/action is allowed in the current context.
- **Orchestrator:** The Core service that plans, validates, schedules, and supervises workflows.

## 3. Collaboration model

A workflow follows this lifecycle:

1. **Understand:** Convert the user's request into a goal, constraints, and expected deliverables.
2. **Plan:** Select skills from the registry and create a DAG. Explain the proposed plan when it materially affects the user.
3. **Validate:** Check skill versions, schemas, dependencies, device capabilities, provider availability, and required permissions before execution.
4. **Execute:** Run ready steps; independent steps may run concurrently within resource and policy limits.
5. **Exchange results:** Each step writes typed artifacts to the Core artifact store. The next step receives only the artifact references and context it needs.
6. **Gate:** Pause at approval-required or high-impact actions. Approval is bound to the exact action payload, target, scope, and expiry.
7. **Verify:** Validate output schemas, evidence, side effects, and postconditions. A model's assertion that a task succeeded is not proof.
8. **Finalize:** Produce a user-facing summary with completed, failed, skipped, and approval-pending steps, plus evidence references.

Illustrative workflow:

```text
User goal
   |
Planner -> Workflow DAG
   |
Validate skills + permissions
   |
[Research] ---- [Device status]      (parallel, independent)
      \             /
       -> [Analysis]
              |
       -> [Policy / risk gate]
              |
       -> [Approval checkpoint]      (only when required)
              |
       -> [Action skill]
              |
       -> [Postcondition verifier]
              |
       -> Final report + audit trail
```

## 4. Contracts and shared state

Skills must not coordinate by relying on undocumented filenames, mutable global variables, or unstructured conversation history.

The Core provides:

- A **Skill Registry** for discovery, versions, trust state, compatibility, and lifecycle.
- A **Workflow Store** for task/step state and dependency edges.
- An **Artifact Store** for immutable, typed inputs and outputs, with provenance and access controls.
- An **Event Bus** for lifecycle events such as `task.created`, `step.completed`, `approval.required`, and `verification.failed`.
- An **Audit Log** for decisions, tool calls, approvals, policy outcomes, retries, and verification evidence.

Each skill manifest declares:
- stable ID, version, display name, description, publisher/source and license;
- supported platforms/runtime and compatibility constraints;
- input and output schema versions;
- required capabilities and requested permissions;
- tools/dependencies, resource limits, and timeout;
- whether network, filesystem, device, or external-account access is needed;
- test fixtures and integrity metadata where available.

Every step has a unique ID and idempotency key. Artifacts carry schema version, producing skill/version, task/step ID, timestamp, provenance, and sensitivity classification. Secrets are passed through scoped secret handles; they must never be placed in prompts, artifacts, logs, or repository files.

## 5. Dependency and execution rules

- A step becomes runnable only when all required dependencies have completed successfully and its conditions are true.
- Independent steps may run in parallel; concurrency is bounded by device resources, user settings, and policy.
- A downstream step receives declared inputs only (least-context principle).
- Missing, invalid, or incompatible artifacts fail the step closed; the orchestrator must not silently guess or fabricate data.
- Retries are bounded, use backoff, and are allowed only for retry-safe/idempotent operations. Non-idempotent actions require reconciliation before retry.
- Cancellation propagates to child steps where safe. Running external actions must report whether cancellation was confirmed or whether their outcome is unknown.
- Workflow plans are versioned. A changed plan creates a new revision; it must not silently rewrite the history of an executing task.
- Cycles, undeclared dependencies, and implicit skill-to-skill execution are rejected.

## 6. Permission and approval model

Permissions are enforced by the Core, not by skill prose. A skill can request capabilities but cannot self-authorize them.

Use risk tiers:

- **Tier 0 — Read-only/local:** ordinary local analysis and access to explicitly provided task data.
- **Tier 1 — Bounded access:** scoped filesystem, network, or app access with explicit resource limits and allowlists.
- **Tier 2 — Sensitive or externally visible:** sending messages, changing account/device settings, publishing, installing software, or modifying user data. Require explicit user approval tied to the exact action.
- **Tier 3 — Critical/destructive:** financial transactions, credential changes, security controls, root-level device operations, irreversible deletion, or broad administrative actions. Deny by default; require a separate high-assurance policy and explicit confirmation where supported. Some operations may remain permanently unsupported.

Approval is not a blanket permission. It is scoped to:
- the exact action and normalized payload;
- target account/device/resource;
- requested capability and limits;
- one execution (unless a narrowly scoped recurring grant is explicitly configured);
- an expiry time and policy version.

Any payload, target, or scope change invalidates the approval. Scheduled/background workflows cannot bypass an approval checkpoint. A denial or policy BLOCK is terminal for that action unless the user changes the request or policy.

## 7. Failure handling and verification

Each step reports one of: `succeeded`, `failed`, `blocked`, `waiting_approval`, `cancelled`, or `unknown_outcome`.

The verifier checks:
- output schema and required fields;
- source/evidence references where the task needs factual support;
- declared postconditions and observable side effects;
- policy compliance and whether approvals match the exact action;
- consistency between claimed result and tool/device response.

A workflow is complete only when required steps and final postconditions pass. Partial success must be reported as partial; unknown outcomes must never be represented as success.

## 8. Skill interoperability

AETHRA's internal manifest and runtime contract are canonical. Import adapters may translate external formats such as Claude `SKILL.md`, Agent Skills, or MCP tool descriptions into candidate skill packages.

Imported skills begin **untrusted / disabled**. Before activation, AETHRA must inspect source and license, parse declared capabilities, scan executable content and dependencies, run static checks and sandboxed tests, and request any required user permissions. Imported instructions are untrusted data: they cannot override Core policy, system boundaries, or approval requirements.

MCP is a tool transport/integration option, not the orchestration authority. Hermes and other agents are optional adapters/participants; AETHRA remains functional without them.

## 9. Android-first runtime constraints

- Run orchestration in a bounded background execution model appropriate to Android lifecycle and foreground-service restrictions.
- Persist workflow state so process death/reboot can resume safely without repeating non-idempotent actions.
- Enforce CPU, memory, storage, network, battery, and thermal budgets.
- Support pause/resume and user-visible active-task status.
- Device-control skills must use narrow platform adapters and explicit capability checks. Root access, where supported, is a separate privileged execution domain and is never implied by ordinary app permissions.
- Windows support can later implement the same Core contracts through a platform adapter.

## 10. Initial implementation sequence

1. Define and validate the Skill Manifest and versioned input/output schemas.
2. Implement the Skill Registry (discover, inspect, enable/disable, version, trust state).
3. Implement the Workflow DAG model and deterministic scheduler (sequential execution first; bounded parallel execution next).
4. Implement typed Artifact Store, Workflow Store, lifecycle events, and audit records.
5. Implement the Core Policy Engine and approval checkpoints before connecting any high-impact tools.
6. Add a verifier and evidence-linked task reports.
7. Add external format importers (starting with `SKILL.md`) and MCP adapters only after the internal contract is stable.
8. Add Android lifecycle/resource controls and process-death recovery.
9. Add agent planning and model-provider routing as replaceable services above the deterministic orchestration core.

## 11. Minimum acceptance tests

- A three-skill workflow passes typed output from skill A to B to C without shared prompt-history assumptions.
- Two independent steps execute concurrently when allowed and are joined only after both finish.
- A failed dependency prevents downstream execution.
- Invalid schema, missing artifact, untrusted skill, or denied capability blocks execution.
- Approval for one payload cannot authorize a modified payload or another target.
- A timeout/restart does not duplicate a non-idempotent action.
- Cancellation and unknown external outcomes are represented accurately.
- Audit records identify skill/version, step, inputs/artifact references, tool calls, policy decision, approval, and verification.
- A workflow can complete using a local/mock model and test tools; no hosted provider is mandatory.
- Imported `SKILL.md` instructions cannot override Core policy or expand permissions.

## 12. What is adopted from trader-skill—and what is not

Adopt:
- small, independently scoped skills;
- explicit producer/consumer relationships;
- structured handoff artifacts;
- composable workflow chains and independent monitoring workflows;
- a dedicated risk/policy gate before sensitive execution;
- tests and transparent user-visible results.

Do not copy as AETHRA's core:
- finance-specific skill names or workflows;
- direct dependence on Claude Code or its permission UI;
- loose shared JSON files as the only coordination mechanism;
- permission enforcement implemented only in natural-language instructions;
- broker-specific execution logic.

**Outcome:** AETHRA gains a general-purpose, model-independent skill collaboration system governed by its own scheduler, artifact contracts, policy engine, and verification layer.

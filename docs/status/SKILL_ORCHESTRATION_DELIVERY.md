# Skill Orchestration Delivery Status

**Updated:** 2026-11-20  
**Work branch:** `feature/skill-orchestration-architecture`  
**Latest implementation commit:** `55d922fb0ec0f647734f779fe183084010fba957`  
**Latest regression-test commit:** `55d922fb0ec0f647734f779fe183084010fba957`  
**Latest verified Core CI:** [Run #110](https://github.com/barjij71/AETHRA/actions/runs/43904764901) at `87382721d0e1837a25bc5aeb73d7ee1caba07d74`  
**Pull request:** [#2 — Core-owned skill orchestration](https://github.com/barjij71/AETHRA/pull/2)

## Verified delivery

The following implementation is present in the work branch and the latest Core changes passed Run #110:

- Versioned `SkillManifest` parsing, field validation, and fail-closed validation of the documented JSON Schema subset.
- The JSON Schema subset validates types, properties, required fields, additional properties, array items, enum/const, patterns, string and numeric bounds, array bounds, and unique items. Unsupported keywords (including external `$ref`) are rejected rather than silently ignored.
- Duplicate names in `required` and duplicate `enum` values are rejected. Enum equality treats numerically equivalent JSON numbers (for example, `1` and `1.0`) as equal.
- Registry registration starts every candidate **untrusted and disabled**; manifest flags do not activate a skill. Trust transitions are Core-module-internal. Revoking trust disables the skill.
- Workflow validation requires a trusted, explicitly enabled registry entry.
- DAG validation, dependency blocking, bounded parallel execution, typed artifact handoff, and output-schema validation before artifact storage.
- File-backed workflow snapshots with revisions, audit records, and stored artifact snapshots.
- Recovery maps an interrupted non-idempotent `RUNNING` step to `UNKNOWN_OUTCOME` instead of invoking its executor again.
- Approval payload matching and one-shot consumption; approval creation/consumption is synchronized, with a concurrent-consumption regression test.
- Tier 3 actions are rejected by workflow validation; the Core approval issuer only issues ordinary approvals for Tier 2. A separate high-assurance Tier 3 path is not implemented.
- Executor-originated cancellation is recorded as `CANCELLED`; dependent steps are blocked. Parent workflow cancellation is rethrown, audited as `workflow.cancelled`, and persisted while active steps remain `RUNNING` so recovery does not falsely claim a terminal result.
- Reconciliation is exposed through the same Core-owned `Orchestrator` instance and its `WorkflowStore`, `ArtifactStore`, and `PolicyEngine`; the lower-level reconciler is module-internal.
- `NOT_EXECUTED` replay is limited to trusted, enabled, non-idempotent skills and requires a one-shot Tier 2 approval. The approval is bound to workflow/task/step, skill ID and version, dependency edges, explicit input artifact IDs and their durable SHA-256 digests, evidence reference/summary, and the step idempotency key.
- Reconciliation rejects a changed evidence payload or changed step input before changing workflow state. A successful `NOT_EXECUTED` decision consumes the approval and records its ID, evidence reference, and idempotency key in the durable audit.
- `SUCCEEDED` reconciliation validates the artifact against the executing skill, task, producer version, provenance, and output schema. The artifact snapshot is copied into the durable workflow record so a newly created Orchestrator can restore it and pass it to downstream steps after restart.
- Regression tests cover simulated process death, `UNKNOWN_OUTCOME`, no automatic replay, approval-required replay, payload/input mismatch rejection, approved resume, and downstream consumption of a reconciled artifact after recreating the file-backed stores.


- Artifact identities are now immutable in the Core `ArtifactStore`: re-inserting an existing ID with identical content is idempotent, while changing payload, provenance, metadata, or producer identity is rejected. Snapshot restoration uses the same guard, preventing durable recovery from replacing an already accepted artifact with conflicting content.
- Regression coverage is present in `ArtifactStoreImmutabilityTest` for identical re-insertion, conflicting payload/provenance, and conflicting snapshot restoration. This change is committed but not yet covered by a new GitHub Actions run at the time of this update.

## Workflow lifecycle event bus

The Core now exposes an injectable `WorkflowEventBus` for orchestrator lifecycle events. `Orchestrator` publishes its existing immutable `AuditEvent` stream to the bus while retaining the durable `WorkflowStore` audit as the source of persisted recovery history. The default `NoOpWorkflowEventBus` keeps the Core provider-independent. `InMemoryWorkflowEventBus` isolates subscriber failures so observers cannot alter execution or policy decisions. Regression tests cover ordered delivery, unsubscribe behavior, and observer-failure isolation.

This event bus is an observation boundary, not a policy bypass: subscribers receive events and cannot mutate workflow state through the bus.

## CI evidence

For current branch head `87382721d0e1837a25bc5aeb73d7ee1caba07d74`:

- [Run #110 — AETHRA Skill Core](https://github.com/barjij71/AETHRA/actions/runs/43904764901): **success**
- Job `core-test`: **success**
- Completed steps: Python source syntax validation, Python policy-boundary tests, `gradle :core:test`, and `gradle :core:compileKotlin`.

Run #110 is associated with the current implementation/test head above. This is Core CI evidence, not an Android APK build result.

## Acceptance status

| Requirement | Evidence/status |
|---|---|
| Three-skill typed handoff | Covered by `threeSkillsExchangeTypedArtifacts` |
| Independent parallel steps | Covered by `parallelIndependentStepsRunConcurrently` |
| Failed dependency blocks downstream | Covered by `failedDependencyBlocksDownstream` |
| Untrusted manifest cannot self-enable | Covered by `SkillRegistryTrustBoundaryTest` |
| Trust revocation disables execution | Covered by `revokingTrustImmediatelyDisablesSkillAndBlocksWorkflow` |
| Payload-bound, one-shot approval | Covered by policy and workflow tests |
| Concurrent one-shot approval | Covered by `PolicyEngineConcurrencyTest` |
| Tier-3 default denial | Implemented; separate high-assurance exception path is not implemented |
| File-backed restart snapshot and audit | Covered by `WorkflowStoreTest` |
| Non-idempotent interrupted step is not replayed automatically | Covered by `nonIdempotentRunningStepRecoversAsUnknownOutcome` |
| Orchestrator-owned reconciliation and safe resume | Covered by `WorkflowReconciliationIntegrationTest` |
| `NOT_EXECUTED` replay requires exact one-shot approval | Covered by `notExecutedReconciliationRequiresExactApprovalAndResumesThroughOrchestratorAfterRestart` |
| Replay approval bound to step inputs and durable artifact digests | Covered by changed-evidence and changed-input rejection in the integration test |
| Reconciled success persists a validated artifact for downstream use after restart | Covered by `reconciledSuccessPersistsValidatedArtifactForDownstreamAfterRestart` |
| Wrong-producer artifact rejected without state mutation | Covered by `reconciledSuccessRejectsArtifactFromAnotherSkillBeforeChangingState` |
| Supported JSON Schema subset and fail-closed keyword handling | Implemented and covered by `JsonSchemaValidatorTest`; full JSON Schema compatibility and broader schema-version compatibility policy remain incomplete |
| Invalid producer output rejected before storage | Covered by `invalidOutputArtifactIsRejectedBeforeStorage` |
| Executor cancellation and downstream blocking | Covered by `executorCancellationIsRecordedAndBlocksDependents` |
| Parent cancellation audit and persisted in-flight state | Covered by `parentCancellationPersistsWorkflowCancellationAndLeavesRunningStepForRecovery` |
| Authenticated human approval UI/identity and durable approval ledger | **Incomplete**; current Core approval store is in-memory and actor identity is supplied by the caller |
| Evidence authenticity, observable side-effect, and postcondition verifier | **Incomplete**; output-schema and artifact provenance checks exist, but they are not a complete external-effect verifier |
| Durable Android-native Workflow/Artifact Store | **Incomplete**; current file store is a JVM implementation, not an Android lifecycle adapter |
| Automatic retry policy and Android-safe full background resume | **Incomplete**; explicit reconciliation/resume is integrated, but bounded retry/backoff policy and Android lifecycle integration remain |
| Independent Event Bus integration | **Incomplete**; audit events are persisted with workflow snapshots |
| `SKILL.md` importer and untrusted MCP adapter | **Not implemented** |
| CPU/RAM/storage/network/battery/thermal controls | **Not implemented** |

## Phase A / Android foundation

The separate [PR #1](https://github.com/barjij71/AETHRA/pull/1) remains open and Draft, and is not mergeable. Its branch `feature/architecture-foundation` is 26 commits ahead and 54 behind current `main`; the compare endpoint reports the branch has diverged. It is not safe to transplant or rebase as part of this Core-only change.

The latest Android CI evidence currently available for that branch is [Run #26](https://github.com/barjij71/AETHRA/actions/runs/42032654286) at `63b86a5c3ed1311777f3aaf7d380473cf4aedd92`:

- Core tests: **success**
- Assemble debug APK: **failure** at `:app:assembleDebug` / `:app:compileDebugKotlin`
- The compiler log reports unresolved Core/API references, duplicate declarations in `Runtime.kt` and `PlatformAdapters.kt`, interface-signature mismatches, and incompatible Skill/Permission/Tool types.

Therefore Phase A is **not accepted as complete**. No Android APK success is claimed. No Android branch or PR #1 changes were made in this run.

## Safety and architecture

AETHRA Core remains the owner of workflow validation, scheduling, artifact handoff, trust gating, policy checks, cancellation state, reconciliation, and recovery state. Hermes, model providers, imported instructions, and MCP are not required by the Core.

The trust-state API is internal to the Core Kotlin module, but this is not a sandbox boundary against arbitrary code already executing inside that module. External adapters must not be given direct registry mutation access.

No changes were made to `main`; PR #2 remains open and Draft.

## 2026-11-17 execution note

The current branch head is `0eb090d59c70bee39dd18e9e67a08774ba11a75c`. GitHub currently reports no status checks or workflow run attached to this SHA yet. Therefore this run must be classified as **implemented, regression-tested in code, CI not yet verified for the current SHA**; the previous Run #110 remains evidence only for its own SHA.


## 2026-11-18 execution note

Workflow lifecycle events are now implemented and regression-tested on the branch. The latest commit is `6337b3e862163222e905b338384399f93754fd72`. GitHub Actions has not yet produced a run associated with this SHA, so this change is **not yet CI-verified**. The previously successful Core run remains evidence only for its own commit.


## 2026-11-20 execution note

The branch head is now `55d922fb0ec0f647734f779fe183084010fba957`. Live GitHub Actions evidence for the previous head `953d00d241d657d20f22f8b2ee23a32c5246ddee` is Run #131, which failed during `core:compileTestKotlin` because `ArtifactStoreImmutabilityTest.kt` used `assertNotNull` without importing it. The failure was a test-compilation defect, not a production Core failure. The missing import was corrected on the current branch head. No workflow run is attached to `55d922fb...` yet, so the current fix remains **implemented, CI pending**.

The authoritative CI workflow is `.github/workflows/skill-core.yml`; it runs Python policy-boundary tests plus `gradle :core:test` and `gradle :core:compileKotlin`. PR #2 remains open/Draft and `main` is unchanged.
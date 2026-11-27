# AETHRA Delivery Phases

This roadmap records verified architecture milestones. A phase is not complete merely because a commit exists; source behavior and tests must support the claim.

## Verified phases

### Phase 5 — Skill Runtime Lifecycle
**Status: VERIFIED**

### Phase 6 — Task Verification / Evidence
**Status: VERIFIED**

### Phase 7 — Tool Execution Capability Contract
**Status: VERIFIED**

### Phase 7 Security Repair
**Status: VERIFIED**

### Phase 7B — Execution Modes
**Status: VERIFIED — RUNNER LOCAL**

ASK, STANDARD, and FULL_ACCESS are execution-mode semantics used by the ToolTaskRunner path. ExecutionMode is not a global Android policy service.

### Phase 8 — Capability Availability
**Status: VERIFIED**

Core defines the platform-neutral `CapabilityAvailability` contract. Android supplies `AndroidCapabilityProvider`.

### Phase 9 — Skill → Task Provenance
**Status: VERIFIED**

`SkillOrchestrator` resolves Skill entrypoints to registered Tools and creates Tasks with Skill provenance that survives persistence/restoration.

### Phase 10 — WorkflowEngine Runtime Exposure
**Status: VERIFIED**

WorkflowEngine is exposed through CoreRegistry/Runtime while TaskEngine remains the authoritative Task execution authority.

## Deferred areas

### Phase 11 — Agent Execution
**Status: NOT READY**

No concrete production requirement proving a separate Agent execution contract has been established.

### Phase 12 — Provider/Model Platform Boundary
**Status: NOT READY**

Provider contracts and routing exist in Core, but there is currently no concrete production Android `LanguageModelProvider` implementation or registered `ProviderFactory`.

## Repository-wide audit

A full source/module/path audit was performed against the current branch.

**Result: NO NEW PHASE REQUIRED.**

This does not mean every future product feature is complete. It means the repository does not currently prove a missing architectural contract that justifies another implementation phase.

## Operating rule

When a future requirement appears, perform read-only contract discovery first. Do not create abstractions, managers, runners, registries, or phases without a concrete consumer and evidence.

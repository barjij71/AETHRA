# AETHRA

**AETHRA is an independent, Android-first Personal AI Execution Environment.**

AETHRA separates Android presentation/runtime integration from a platform-neutral Core.

````

Android → Runtime → Core → Task → Skills / Tools
                              ↓
                    Capability / Policy / Permission
                              ↓
                    Provider / Model (optional)
                              ↓
                    Verification → Persistence / Events
````


## Current verified status

The current repository contains a Kotlin/JVM Core and an Android application integration.

Verified milestones:

- Phase 5 — Skill Runtime lifecycle: **VERIFIED**
- Phase 6 — Task verification/evidence/postconditions: **VERIFIED**
- Phase 7 — Tool execution capability contract: **VERIFIED**
- Phase 7 Security Repair — authorization/security ordering: **VERIFIED**
- Phase 7B — execution modes, runner-local: **VERIFIED**
- Phase 8 — capability availability + Android capability provider: **VERIFIED**
- Phase 9 — Skill → Task provenance: **VERIFIED**
- Phase 10 — WorkflowEngine exposure through Core/Runtime: **VERIFIED**
- Phase 11 — Agent execution contract: **NOT READY**
- Phase 12 — Provider/Model platform implementation: **NOT READY**
- Repository-wide audit: **NO NEW PHASE REQUIRED**

Current development branch:

````

feature/phase-7-tool-execution-contract
````


## Repository structure

````

core/
  src/main/kotlin/org/aethra/core/
    agents/
    domain/
    execution/
    external/
    memory/
    providers/
    runtime/
    security/
    skills/
    storage/
    task/
    tools/
    workflow/

app/
  src/main/java/org/aethra/assistant/aethra/
    adapters/
    memory/
    navigation/
    permissions/
    runtime/
    security/
    skills/
    tools/
    ui/

docs/
skills/
config/
.github/
````


`core` is platform-neutral Kotlin/JVM code. Android-specific implementations belong under `app`.

## Execution authority

The authoritative Task execution path is:

````

Android UI
  → Runtime
  → CoreRegistry
  → TaskEngine
  → AndroidTaskDispatcher / appropriate TaskRunner
  → authorization / capability checks
  → operation
  → TaskVerifier
  → persistence
````


`TaskEngine` remains the authoritative Task execution authority. WorkflowEngine is orchestration; SkillOrchestrator is the Skill-to-Task boundary.

## Provider status

Provider/model contracts and routing exist in Core, but no concrete Android `LanguageModelProvider` is currently registered in production.

Provider credentials, network transport, and a production model adapter remain intentionally deferred until a concrete production requirement exists.

Test-only `FakeProvider` is not a production model service.

## Development rules

- Inspect the current repository before editing.
- Preserve the canonical workspace and branch.
- Do not use destructive Git operations to synchronize with GitHub.
- Do not bypass TaskEngine, policy, capability, permission, verification, or persistence boundaries.
- Do not add abstractions merely for symmetry.
- Do not claim a phase is complete without source/test evidence.
- Keep commits focused and leave the working tree clean.

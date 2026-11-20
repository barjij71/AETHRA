# Workflow: policy

## Goal
Implement and verify AETHRA's permission and policy layer: `PermissionBroker`, `PolicyEngine`, `ExecutionPolicy`, risk classes, approval gates, and audit recording.

## Scope
Applies to `core/src/main/kotlin/org/aethra/core/security/` (PermissionBroker, AuditLog), `app/src/main/java/**/security/` (PolicyEngine, ExecutionPolicy, PermissionBridge), and permission tier enforcement. Does not cover Android permission runtime (that is Android OS) or UI.

## Triggers
- File patterns: `core/src/main/kotlin/org/aethra/core/security/**`, `app/src/main/java/**/security/**`, `app/src/main/java/**/permissions/**`
- Intent phrases: "permission broker", "policy engine", "risk class", "approval gate", "policy reject", "audit log", "approval required"

## Inputs
- `PermissionBroker.kt` (requestApproval, recordDecision)
- `AuditLog.kt` (record, load)
- `PolicyEngine.kt` (allows for risk/tool/permissionId)
- `ExecutionPolicy.kt` (execute: policy check → approval → run → audit)
- `RiskClass.kt` (READ_ONLY, REVERSIBLE, SENSITIVE, DESTRUCTIVE with levels)
- Contract tests under `core/src/test/kotlin/org/aethra/core/security/`

## Invariants
- Model/agent output is untrusted input; cannot directly execute or grant permissions
- `PolicyEngine.allows()` rejects DESTRUCTIVE risk class and disallowed patterns
- Approval-required or SENSITIVE+ tools fail with `permission_required:` token when no approval granted
- `ExecutionPolicy` audits grant, reject, success, and failure for every tool execution
- Agents cannot grant themselves permissions
- Audit records are immutable (prepended to journal)

## Procedure
1. Inspect PermissionBroker, PolicyEngine, ExecutionPolicy, PermissionBridge
2. Implement the change (minimal, one logical change)
3. Add or update a permission broker contract test
4. Run `:core:test --rerun` locally; all tests pass
5. Confirm review gate
6. Commit `fix(core): ...` or `feat(android): ...`
7. Push; confirm CI green

## Outputs
- Modified policy/permission code
- New or updated security test(s)
- Green CI Core Contract Tests

## Review gate
- [ ] `:core:test --rerun` passes locally
- [ ] DESTRUCTIVE risk class is always rejected
- [ ] Approval-required tools fail truthfully without approval UI
- [ ] Every tool execution is audited (grant/reject/success/failure)
- [ ] Agents cannot self-grant permissions
- [ ] CI green on `origin/codex/phase-c-handoff`

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `skills/aethra/workflows/tools/SKILL.md` — tool dispatch (related)
- `skills/aethra/workflows/android-runtime/SKILL.md` — Android permission bridge (related)
- `docs/security/EXECUTION_MODEL.md` — permission tiers and trust boundaries

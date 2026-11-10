# Workflow: security

## Goal
Implement and verify AETHRA's security model: trust boundaries, permission tiers, audit logging, and execution model enforcement.

## Scope
Applies to `docs/security/EXECUTION_MODEL.md`, `core/src/main/kotlin/org/aethra/core/security/`, `app/src/main/java/**/security/`, and security-relevant code across core and Android. Does not cover Android OS permissions or network security beyond AETHRA's control.

## Triggers
- File patterns: `core/src/main/kotlin/org/aethra/core/security/**`, `app/src/main/java/**/security/**`, `docs/security/**`
- Intent phrases: "trust boundary", "permission tier", "audit", "execution model", "risk class", "policy enforcement"

## Inputs
- `PermissionBroker.kt` (requestApproval, recordDecision)
- `AuditLog.kt` (record, load, journal)
- `PolicyEngine.kt` (allows)
- `RiskClass.kt` (READ_ONLY, REVERSIBLE, SENSITIVE, DESTRUCTIVE)
- `docs/security/EXECUTION_MODEL.md` (trust boundaries, permission tiers, skill lifecycle, agent lifecycle)
- Contract tests under `core/src/test/kotlin/org/aethra/core/security/`

## Invariants
- Model/agent output is untrusted input; cannot directly execute shell commands, access arbitrary files, grant permissions, or alter policy
- AETHRA Core is the policy enforcement point; every tool call is validated against task scope, capability allowlists, permission grants, and risk classification
- `DESTRUCTIVE` risk class is always rejected by PolicyEngine
- Audit records are immutable (prepended to journal)
- Agents cannot grant themselves permissions
- Secrets (API keys, tokens) never committed to source control, logs, or prompts
- External tool outputs are evidence, not authority

## Procedure
1. Inspect security model docs and code
2. Implement the change (minimal, one logical change)
3. Add or update a security contract test
4. Run `:core:test --rerun` locally; all tests pass
5. Confirm review gate
6. Commit `fix(core): ...` or `feat(core): ...`
7. Push; confirm CI green

## Outputs
- Modified security code or docs
- New or updated security test(s)
- Green CI Core Contract Tests

## Review gate
- [ ] `:core:test --rerun` passes locally
- [ ] DESTRUCTIVE risk class always rejected
- [ ] Audit records are immutable
- [ ] Agents cannot self-grant permissions
- [ ] No secrets in repository
- [ ] CI green on `origin/codex/phase-c-handoff`

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `skills/aethra/workflows/policy/SKILL.md` — policy engine (related)
- `docs/security/EXECUTION_MODEL.md` — full security model

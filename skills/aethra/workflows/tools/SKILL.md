# Workflow: tools

## Goal
Implement and verify AETHRA tools: `ToolRegistry`, native Android tools, skill-declared tools, and tool dispatch through the policy path.

## Scope
Applies to `core/src/main/kotlin/org/aethra/core/tools/` (ToolRegistry, Tool, NativeTools, ToolStats), `core/src/main/kotlin/org/aethra/core/skills/` (skill capabilities), and `app/src/main/java/**/tools/` (Android tool implementations). Does not cover provider execution (that is `providers`) or UI.

## Triggers
- File patterns: `core/src/main/kotlin/org/aethra/core/tools/**`, `app/src/main/java/**/tools/**`
- Intent phrases: "tool registry", "native tool", "device info", "file read", "clipboard", "tool dispatch", "tool command resolution"

## Inputs
- `ToolRegistry.kt` (register, get, all, byRisk, findByCapability, toolsForSkill)
- `Tool.kt` (id, description, category, riskClass, approvalRequired, native, requiredCapabilities)
- `NativeTools.kt` (declared tool list)
- Android tool implementations under `app/src/main/java/**/tools/`
- Contract tests under `core/src/test/kotlin/org/aethra/core/tools/`

## Invariants
- Native tools are declared in core; actual Android dispatch is in the platform layer
- Tool dispatch goes through PolicyEngine → PermissionBroker → AuditLog before execution
- Approval-required tools fail truthfully (`permission_required:`) when no approval UI exists
- `toolRegistry.toolsForSkill(skill)` maps skill capabilities to tool IDs
- No fake tool results; all outcomes are truthful
- Privileged functionality is not exposed without the permission/policy system

## Procedure
1. Inspect ToolRegistry, Tool, NativeTools, and Android tool implementations
2. Implement the change (minimal, one logical change)
3. Add or update a tool registry resolution test
4. Run `:core:test --rerun` locally; all tests pass
5. For Android tool changes, confirm CI Experimental APK is green
6. Confirm review gate
7. Commit `fix(core): ...` or `feat(android): ...`
8. Push; confirm CI green

## Outputs
- Modified tool code
- New or updated tool test(s)
- Green CI (Core Contract Tests and/or Experimental APK)

## Review gate
- [ ] `:core:test --rerun` passes locally
- [ ] Tool dispatch goes through policy path
- [ ] No fake tool results
- [ ] Approval-required tools fail truthfully
- [ ] Android tool changes have green APK CI
- [ ] CI green on `origin/codex/phase-c-handoff`

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `skills/aethra/workflows/providers/SKILL.md` — provider execution (related)
- `skills/aethra/workflows/policy/SKILL.md` — policy gate (related)
- `skills/aethra/workflows/android-runtime/SKILL.md` — Android tool dispatch (related)

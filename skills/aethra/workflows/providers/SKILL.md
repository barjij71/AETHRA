# Workflow: providers

## Goal
Implement and verify AI provider integration: `ProviderModelRouter`, routing decisions, fallback candidates, provider execution, and truthful error reporting.

## Scope
Applies to `core/src/main/kotlin/org/aethra/core/providers/` (ProviderModelRouter, ProviderRegistry, LanguageModelProvider, RoutingDecision, FallbackDecision, ConversationProviderConfig) and `ProviderTaskRunner`. Does not cover Android app code or UI.

## Triggers
- File patterns: `core/src/main/kotlin/org/aethra/core/providers/**`, `core/src/main/kotlin/org/aethra/core/task/ProviderTaskRunner.kt`
- Intent phrases: "provider", "model", "routing", "fallback", "UnableToRoute", "provider chat", "model selection"

## Inputs
- `ProviderModelRouter.kt` (resolve, getFallbackCandidate)
- `ProviderRegistry.kt` (resolveProvider)
- `LanguageModelProvider.kt` (chat)
- `RoutingDecision.kt` (Success, Rejected, UnableToRoute)
- `FallbackDecision.kt` (Denied, Candidate)
- `ProviderTaskRunner.kt` (executeProvider, checkAndRunFallback)
- Contract tests under `core/src/test/kotlin/org/aethra/core/providers/`

## Invariants
- `UnableToRoute` returns `Result.failure` with a truthful reason — never pretends success
- `Rejected` returns `Result.failure` with policy rejection reason
- Fallback is only attempted when primary fails AND a fallback candidate is granted
- Fallback provider not registered returns `Result.failure("Fallback provider not registered")`
- Provider exceptions are caught and returned as `Result.failure`, not thrown
- Audit log records primary failure and fallback outcomes
- No fake provider call results; all outcomes are truthful

## Procedure
1. Inspect provider router, registry, and ProviderTaskRunner
2. Implement the change (minimal, one logical change)
3. Add or update a provider execution contract test
4. Run `:core:test --rerun` locally; all tests pass
5. Confirm review gate
6. Commit `fix(core): ...` or `feat(core): ...`
7. Push; confirm CI green

## Outputs
- Modified provider code
- New or updated provider test(s)
- Green CI Core Contract Tests

## Review gate
- [ ] `:core:test --rerun` passes locally
- [ ] UnableToRoute/Rejected return truthful failures
- [ ] Fallback only on granted candidate
- [ ] Audit records primary and fallback outcomes
- [ ] No fake provider results
- [ ] CI green on `origin/codex/phase-c-handoff`

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `skills/aethra/workflows/core/SKILL.md` — ProviderTaskRunner execution (related)
- `skills/aethra/workflows/policy/SKILL.md` — policy rejection (related)

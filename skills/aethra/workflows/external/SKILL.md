# Workflow: external

## Goal
Implement and verify AETHRA external capabilities: `ExternalCapability`, `ExternalCapabilityRegistry`, `ExternalCapabilityRouter`, `ExternalCapabilityPolicy`, and external adapters (InstaBox, GitHub, MCP).

## Scope
Applies to `core/src/main/kotlin/org/aethra/core/external/` and external capability adapters. External capabilities are integrations/adapters around AETHRA, not replacements for AETHRA Core.

## Triggers
- File patterns: `core/src/main/kotlin/org/aethra/core/external/**`
- Intent phrases: "external capability", "capability router", "capability registry", "InstaBox", "remote computer", "MCP", "GitHub integration"

## Inputs
- `ExternalCapability.kt` (interface, ExternalCapabilityType, CapabilityHealth, CapabilityRequest, CapabilityResult, ExternalCapabilityPolicy)
- `ExternalCapabilityRegistry.kt` (register, unregister, get, all, byType)
- `ExternalCapabilityRouter.kt` (execute: resolve → policy check → capability check → execute)
- `InstaBoxRemoteComputerProvider.kt` (health, execute, machine lifecycle, remote exec)
- `InstaBoxTransport.kt` / `UrlConnectionInstaBoxTransport.kt` (HTTP boundary)
- `InstaBoxApiKeyProvider` (fun interface for API key injection)
- Contract tests under `core/src/test/kotlin/org/aethra/core/external/`

## Invariants
- External capabilities are adapters, not runtime prerequisites (A-001)
- Core never depends on provider implementations
- `ExternalCapabilityPolicy.allows()` denies mining, crypto, port scan, DoS, phishing, credential stuffing, spam, account creation
- Router rejects unknown capability IDs and unsupported operations
- API key is injected via `InstaBoxApiKeyProvider`, never persisted by the provider
- `UrlConnectionInstaBoxTransport` has no credential storage; default base URL is `https://api.instabox.sh`
- No real InstaBox API key or credential committed to the repository

## Procedure
1. Inspect external capability files and adapter
2. Implement the change (minimal, one logical change)
3. Add or update an external capability contract test
4. Run `:core:test --rerun` locally; all tests pass
5. Confirm review gate
6. Commit `fix(core): ...` or `feat(core): ...`
7. Push; confirm CI green

## Outputs
- Modified external capability code
- New or updated external capability test(s)
- Green CI Core Contract Tests

## Review gate
- [ ] `:core:test --rerun` passes locally
- [ ] External capability policy denies blocked operations
- [ ] Router rejects unknown capabilities and unsupported operations
- [ ] API key is injected, never persisted
- [ ] No real credentials in repository
- [ ] CI green on `origin/codex/phase-c-handoff`

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `skills/aethra/workflows/instabox/SKILL.md` — InstaBox adapter (related)
- `skills/aethra/workflows/github/SKILL.md` — GitHub integration (related)
- `skills/aethra/workflows/mcp/SKILL.md` — MCP integration (related)
- `docs/architecture/DECISIONS.md` — A-001 Independent AETHRA Core

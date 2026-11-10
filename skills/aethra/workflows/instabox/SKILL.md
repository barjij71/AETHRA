# Workflow: instabox

## Goal
Implement and verify the InstaBox remote computer adapter: transport, machine lifecycle operations, remote execution, credential boundary, and health checks.

## Scope
Applies to `core/src/main/kotlin/org/aethra/core/external/InstaBoxRemoteComputerProvider.kt`, `InstaBoxTransport.kt`, `UrlConnectionInstaBoxTransport.kt`, `InstaBoxApiKeyProvider`. InstaBox is a remote execution environment accessed through AETHRA's external capability architecture — it is not the source of truth for AETHRA code.

## Triggers
- File patterns: `core/src/main/kotlin/org/aethra/core/external/InstaBox*`
- Intent phrases: "InstaBox", "transport", "machine lifecycle", "remote exec", "API key", "credential boundary", "health check"

## Inputs
- `InstaBoxRemoteComputerProvider.kt` (health, execute, machineGet, lifecycle, exec)
- `InstaBoxTransport.kt` (request interface, InstaBoxHttpResponse, InstaBoxApiKeyProvider fun interface)
- `UrlConnectionInstaBoxTransport.kt` (HttpURLConnection implementation, base URL)
- Contract tests under `core/src/test/kotlin/org/aethra/core/external/`

## Invariants
- InstaBox is treated as an external capability/provider, not a source of truth for AETHRA source code
- `InstaBoxApiKeyProvider.apiKey()` is injected per request; never persisted by the provider
- `UrlConnectionInstaBoxTransport` contains no credential storage
- Default base URL is `https://api.instabox.sh`
- No real InstaBox API key or credential committed to the repository
- Transport is injectable for deterministic Core tests
- All InstaBox operations go through `ExternalCapabilityRouter` policy check first

## Procedure
1. Inspect InstaBox provider and transport files
2. Implement the change (minimal, one logical change)
3. Add or update an InstaBox contract test using injectable transport
4. Run `:core:test --rerun` locally; all tests pass
5. Confirm review gate
6. Commit `fix(core): ...` or `feat(core): ...`
7. Push; confirm CI green

## Outputs
- Modified InstaBox provider/transport code
- New or updated InstaBox test(s)
- Green CI Core Contract Tests

## Review gate
- [ ] `:core:test --rerun` passes locally
- [ ] Transport is injectable for testing
- [ ] No credentials in repository
- [ ] API key checked before every operation
- [ ] All operations return `Result` (success or truthful failure)
- [ ] CI green on `origin/codex/phase-c-handoff`

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `skills/aethra/workflows/external/SKILL.md` — external capability contracts (related)
- `docs/security/EXECUTION_MODEL.md` — Linux and Root section

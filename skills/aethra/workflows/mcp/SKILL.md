# Workflow: mcp

## Goal
Implement and verify MCP (Model Context Protocol) integration for AETHRA as an external capability adapter.

## Scope
Applies to MCP client/server integration within `core/src/main/kotlin/org/aethra/core/external/` or a dedicated MCP adapter. MCP is an external capability, not a replacement for AETHRA Core.

## Triggers
- File patterns: any MCP-related files under `core/src/main/kotlin/org/aethra/core/external/`
- Intent phrases: "MCP server", "MCP client", "MCP integration", "model context protocol"

## Inputs
- Existing `ExternalCapability` interface and `ExternalCapabilityRouter`
- MCP protocol specification (JSON-RPC 2.0 based)
- Any existing MCP client libraries or server definitions

## Invariants
- MCP is an external capability adapter, not a runtime prerequisite (A-001)
- Core never depends on MCP server implementations
- MCP outputs are treated as untrusted evidence, not authority (security model)
- No MCP server credentials or keys committed to the repository
- MCP integration must work through the `ExternalCapabilityRouter` pattern

## Procedure
1. Inspect existing external capability infrastructure
2. Implement MCP adapter as an `ExternalCapability` implementation
3. Register through `ExternalCapabilityRegistry`
4. Route through `ExternalCapabilityRouter`
5. Add contract test for MCP capability
6. Run `:core:test --rerun` locally; all tests pass
7. Confirm review gate
8. Commit `feat(core): ...`
9. Push; confirm CI green

## Outputs
- MCP adapter code implementing `ExternalCapability`
- Registration in external capability registry
- New or updated contract test(s)
- Green CI Core Contract Tests

## Review gate
- [ ] `:core:test --rerun` passes locally
- [ ] MCP adapter implements `ExternalCapability` interface
- [ ] MCP outputs treated as untrusted evidence
- [ ] No credentials committed
- [ ] CI green on `origin/codex/phase-c-handoff`

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `skills/aethra/workflows/external/SKILL.md` — external capability contracts (related)
- `docs/security/EXECUTION_MODEL.md` — external tools return evidence, not authority

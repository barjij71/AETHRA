# Workflow: memory

## Goal
Implement and verify AETHRA Core memory: `MemoryEngine`, persistence to Storage, store → persist → reload across engine instances, and review/export/delete controls.

## Scope
Applies to `core/src/main/kotlin/org/aethra/core/memory/MemoryEngine.kt`, `MemoryEntry`, `MemoryCategory`, `MemoryResult`, and Storage persistence key `aethra.memory.v1`. Does not cover Android `MemoryAndroid` (that is `android-runtime`) or UI.

## Triggers
- File patterns: `core/src/main/kotlin/org/aethra/core/memory/**`
- Intent phrases: "memory engine", "store memory", "persist memory", "reload memory", "search memory", "memory survives restart"

## Inputs
- `MemoryEngine.kt` (initialize, add, update, delete, search, all, byCategory, persist, load)
- `MemoryEntry.kt` (id, category, text, source, taskId, agentId, createdAt)
- `MemoryCategory.kt` (PREFERENCES, PROJECT, TASK, AGENT, KNOWLEDGE, SHORT_TERM)
- Storage interface (`getString`, `setString`)
- Contract tests under `core/src/test/kotlin/org/aethra/core/memory/`

## Invariants
- Memory persists to Storage via `aethra.memory.v1` JSON key
- `initialize()` loads persisted entries into memory index
- `add()` is idempotent: same text + source overwrites old entry
- `search()` returns ranked results by keyword frequency and recency
- `delete()` removes by id; `update()` replaces by id
- No synthetic startup memories are injected; all memories come from real operations

## Procedure
1. Inspect `MemoryEngine.kt` and Storage integration
2. Implement the change (minimal, one logical change)
3. Add or update a deterministic test for store → persist → reload across engine instances
4. Run `:core:test --rerun` locally; all tests pass
5. Confirm review gate
6. Commit `fix(core): ...` or `feat(core): ...`
7. Push; confirm CI green

## Outputs
- Modified memory engine code
- New or updated memory test(s)
- Green CI Core Contract Tests

## Review gate
- [ ] `:core:test --rerun` passes locally
- [ ] Store → persist → reload works across engine instances on same Storage
- [ ] No synthetic startup memories injected
- [ ] Search returns ranked results
- [ ] No tests weakened or removed
- [ ] CI green on `origin/codex/phase-c-handoff`

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `skills/aethra/workflows/android-runtime/SKILL.md` — MemoryAndroid (related)
- `docs/security/EXECUTION_MODEL.md` — local-first memory section

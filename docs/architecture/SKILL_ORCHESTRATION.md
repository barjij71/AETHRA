# AETHRA Skill Orchestration

## Authority Boundary

AETHRA Core owns skill identity, contract validation, trust, activation, workflow orchestration, permissions, evidence, postconditions, and final outcome status.

External sources such as `SKILL.md`, MCP servers, Hermes, model providers, or remote metadata are **adapters only**. They cannot:
- Grant trust
- Enable a skill
- Approve permissions
- Declare a workflow successful

The authority chain is strictly:

```
Skill (data only)
    ↓
SkillManifest.parse() → validation only
    ↓
SkillRegistry.registerManifest() → Core-owned ingestion boundary
    ↓
Skill (UNTRUSTED, disabled, DISCOVERED)
    ↓
SkillRegistry.install() → lifecycle transition (never grants trust)
    ↓
SkillRegistry.trust() → Core verification gate (internal)
    ↓
SkillRegistry.enable() → execution eligibility (requires TRUSTED + INSTALLED or DISABLED)
    ↓
ToolRegistry → ExecutionPolicy → PermissionBroker → AuditLog
    ↓
TaskEngine (sole execution authority)
```

---

## SkillManifest Contract

**Current Core contract version: 1.0**

### Required JSON Fields
- `contractVersion` — must be `"1.0"`
- `id` — non-blank string, unique skill identifier
- `name` — non-blank string, human-readable name
- `version` — non-blank string, semantic version

### Optional Fields
- `entrypoints` — array of strings
- `capabilities` — array of strings
- `requestedPermissions` — array of strings (metadata only, not auto-granted)
- `dependencies` — array of strings (metadata only, no resolution in core)
- `platforms` — array of strings
- `riskClass` — one of: `READ_ONLY`, `REVERSIBLE`, `SENSITIVE`, `DESTRUCTIVE`

### Validation Rules
- Unknown fields are **rejected**
- Unsupported `contractVersion` is **rejected**
- Malformed arrays are **rejected**
- Blank required values are **rejected**
- Unsupported `riskClass` values are **rejected**

### Parsing vs Trust
```
SkillManifest.parse(rawJson) → Result<SkillManifest>
    ↓ (validation only, no trust grant)
manifest.toUntrustedSkill() → Skill(UNTRUSTED, disabled, DISCOVERED)
    ↓
SkillRegistry.registerManifest() → persists UNTRUSTED
```

**Parsing is validation only.** Conversion to a Core `Skill` always produces an **UNTRUSTED, disabled, DISCOVERED** skill before persistence. Trust and activation remain Core-owned operations.

---

## Lifecycle State Machine

### States
| State | Description | isInstalled | isActive (potential) |
|-------|-------------|-------------|---------------------|
| `DISCOVERED` | Registered, awaiting installation | false | false |
| `VALIDATING` | Under async validation (reserved) | false | false |
| `VALID` | Validation passed, not installed (reserved) | false | false |
| `INSTALLING` | Installation in progress (reserved) | false | false |
| `INSTALLED` | Ready for trust/enable | **true** | requires `trust()` + `enable()` |
| `UPDATING` | Update in progress (reserved) | **true** | requires `trust()` + `enable()` |
| `UPDATE_AVAILABLE` | New artifact exists, not verified | false | false |
| `DISABLED` | Explicitly disabled by user/Core | false | false |
| `FAILED` | Installation/execution failed | false | false |
| `REMOVED` | Deleted from registry | false | false |

### Transitions

```
                    ┌─────────────────────────────────────────┐
                    │         EXTERNAL VERIFICATION            │
                    │  (checksum, signature, review, CI, etc.) │
                    └─────────────────────────────────────────┘
                                           │
                                           ▼
    ┌─────────┐    install()     ┌──────────┐    trust()    ┌──────────┐
    │DISCOVERED├─────────────────►│INSTALLED├───────────────►│ TRUSTED  │
    │  VALID   │                  │UNTRUSTED  │  (internal)  │          │
    │VALIDATING│                  │disabled   │              │          │
    └─────────┘                  └─────────┬┘              └────┬─────┘
          ▲                                 │                   │
          │                                 │                   │
          │    markUpdateAvailable()        │                   │
          └─────────────────────────────────┘                   │
                         │                                      │
                         ▼                                      │
              ┌──────────────────┐                              │
              │UPDATE_AVAILABLE  │                              │
              │  disabled        │                              │
              │  trust preserved │                              │
              │  (identity only) │                              │
              └────────┬─────────┘                              │
                       │                                        │
                       │ external verification                  │
                       │ install() resets trust                 │
                       ▼                                        │
              ┌──────────────────┐                              │
              │INSTALLED         │◄─────────────────────────────┘
              │UNTRUSTED         │
              │disabled          │
              └──────────────────┘
```

### Core Operations

| Operation | From States | To State | trustState | enabled | isActive | Notes |
|-----------|-------------|----------|------------|---------|----------|-------|
| `install()` | `DISCOVERED`, `VALID`, `VALIDATING`, `UPDATE_AVAILABLE` | `INSTALLED` | `UNTRUSTED` (forced) | `false` (forced) | `false` | **Lifecycle only** — no artifact verification |
| `install()` | `INSTALLED` | `INSTALLED` | unchanged | unchanged | unchanged | Idempotent |
| `markUpdateAvailable()` | `INSTALLED` | `UPDATE_AVAILABLE` | **preserved** (identity trust) | `false` (forced) | `false` | Does NOT make new artifact trusted |
| `enable()` | `INSTALLED` + `TRUSTED` | `INSTALLED` | unchanged | `true` | `true` | Normal activation |
| `enable()` | `DISABLED` + `TRUSTED` | `INSTALLED` | unchanged | `true` | `true` | Re-activation |
| `disable()` | `INSTALLED` + `enabled=true` | `DISABLED` | unchanged | `false` | `false` | Explicit state |
| `disable()` | `DISABLED` | `DISABLED` | unchanged | `false` | `false` | Idempotent |
| `trust()` | * | same | `TRUSTED` | recomputed | recomputed | Internal only |
| `revokeTrust()` | `TRUSTED` | same | `REVOKED` | `false` | `false` | Immediate disable |
| `remove()` | * | `REMOVED` (deleted) | — | — | — | Permanent |

### Rejected Transitions
- `enable()` from `UPDATE_AVAILABLE`, `UPDATING`, `UNTRUSTED`, `REVOKED`, `DISCOVERED`, `VALID`, `VALIDATING`, `FAILED`, `REMOVED`
- `disable()` from `UPDATE_AVAILABLE`, `UPDATING`, `REMOVED` (no mutation, no persistence)
- `markUpdateAvailable()` from non-`INSTALLED` states (including `UPDATING`, `UPDATE_AVAILABLE`, `DISABLED`, `FAILED`, `REMOVED`)
- `install()` from `REMOVED`, `DISABLED`, `UPDATING`, `FAILED`

---

## Install / Verification / Trust Sequence

```
┌─────────────────────────────────────────────────────────────────┐
│                    ARTIFACT VERIFICATION                        │
│  (External: checksum, signature, review, CI pipeline)          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      install()                                   │
│  State: INSTALLED                                               │
│  trustState: UNTRUSTED (forced reset)                           │
│  enabled: false                                                 │
│  isActive: false                                                │
│  → NO artifact validation happens here                          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      trust()                                     │
│  (Core internal verification gate)                              │
│  trustState: TRUSTED                                            │
│  enabled: recomputed (state.isInstalled && enabled)             │
│  → Explicit human/code review decision                          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      enable()                                    │
│  State: INSTALLED (explicit)                                    │
│  enabled: true                                                  │
│  isActive: true                                                 │
│  → Skill is now execution-eligible                              │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              ToolRegistry → ExecutionPolicy →                   │
│              PermissionBroker → AuditLog → TaskEngine           │
└─────────────────────────────────────────────────────────────────┘
```

**Key Principle**: `install()` is a **lifecycle transition only**. It does **not**:
- Verify checksums
- Verify signatures
- Validate artifact contents
- Approve versions
- Grant trust
- Enable execution

The security sequence is: **external verification → install() → UNTRUSTED → trust() → TRUSTED → enable()**

---

## Update / Trust Model

### Identity Trust vs Artifact Trust

The current model has **identity trust only** (`trustState` bound to `skill.id`), **not artifact/version trust**.

**`trustState` represents identity-level trust metadata only.** It does **not** represent cryptographic artifact-level verification. A `TRUSTED` trustState means "Core has approved this skill identity as safe to execute," but it does **not** independently prove a specific artifact's integrity or checksum. Artifact-level trust requires external verification (checksum, signature, review) before `install()` + `trust()` + `enable()`.

| Scenario | trustState | Meaning |
|----------|------------|---------|
| `INSTALLED` + `TRUSTED` + `enabled=true` | `TRUSTED` | "Core has approved this skill identity as safe to execute" |
| `markUpdateAvailable()` | `TRUSTED` preserved | "A new artifact exists for this identity, but has NOT been verified" |
| `install(new_version)` | **RESET to `UNTRUSTED`** | "New artifact with same identity requires re-verification" |

### Why This Design?

- `TRUSTED` means: "Core has approved this **skill identity** as safe to execute"
- A new version with the same `id` is a **different artifact** — integrity may differ
- Preserving `TRUSTED` across versions would allow silent upgrade to executable without verification
- The model **cannot represent artifact-level trust** without adding `verifiedVersion` or `verifiedIntegrityHash` fields

### Required for New Version to Become Executable

1. External process verifies new artifact (checksum, signature, review)
2. `markUpdateAvailable()` — signals update exists, disables current
3. `install()` — transitions to `INSTALLED`, **resets trust to `UNTRUSTED`**
4. Core verification → `trust()` → `TRUSTED`
5. `enable()` → executable

**No new API** — existing `install()` + `trust()` + `enable()` chain is sufficient when `install()` resets trust.

---

## Enable / Disable Semantics

### Enable
- **Allowed**: `INSTALLED + TRUSTED` → `enabled=true`, `isActive=true`
- **Allowed**: `DISABLED + TRUSTED` → `INSTALLED + enabled=true`, `isActive=true` (re-activation)
- **Rejected**: `UPDATE_AVAILABLE`, `UPDATING`, `UNTRUSTED`, `REVOKED`, non-installed states, `REMOVED`

### Disable
- **Normal**: `INSTALLED + enabled=true` → `DISABLED + enabled=false`, `isActive=false` (explicit state)
- **Idempotent**: `DISABLED` → `DISABLED` (returns true, no state change)
- **Rejected** (no mutation, no persistence): `UPDATE_AVAILABLE`, `UPDATING`, `REMOVED`
- **Rejected**: other non-applicable states

### Why Reject `disable()` on `UPDATE_AVAILABLE`/`UPDATING`?

- `UPDATE_AVAILABLE` already guarantees `enabled=false` ∧ `isActive=false`
- Calling `disable()` would **lose semantic information** ("update available" ≠ "disabled")
- `UPDATING` is a transient in-progress state; mutations should wait
- Both are **no-op, return false, no persistence**

---

## Persistence / Rollback Contract

Every mutating operation follows:

```
mutate in-memory
    ↓
persistState()
    ↓
success → commit
failure → restore exact previous state → throw SkillPersistenceException
```

### Applied Consistently To
- `register()`
- `install()`
- `markUpdateAvailable()`
- `enable()`
- `disable()`
- `trust()` / `revokeTrust()`
- `remove()`

### Load / Recovery Behavior
| Persisted State | On Restart | isActive | Notes |
|-----------------|------------|----------|-------|
| `INSTALLED` + `TRUSTED` + `enabled=true` | Same | `true` | Executable immediately |
| `INSTALLED` + `TRUSTED` + `enabled=false` | Same | `false` | Requires `enable()` |
| `DISABLED` + `TRUSTED` + `enabled=false` | Same | `false` | Requires `enable()` → `INSTALLED` |
| `UPDATE_AVAILABLE` + `TRUSTED` + `enabled=false` | Same | `false` | **Not executable**; requires `install()` (resets trust) |
| `FAILED` | Same | `false` | Not executable |
| `REMOVED` | Not loaded | N/A | Deleted |
| Corrupt journal | **Throws `SkillPersistenceException`** | N/A | Fail-closed — no silent recovery |

### Duplicate ID Detection
On load, duplicate skill IDs in the persisted journal throw `SkillPersistenceException("Duplicate skill id in persisted journal: ...")` — fail-closed.

---

## TaskEngine Remains Sole Task Execution Authority

**Skills do not execute.**

| What Skills Are | What Skills Are NOT |
|-----------------|---------------------|
| Data containers declaring capabilities | Execution engines |
| Metadata for ToolRegistry mapping | Task runners |
| Policy inputs for ExecutionPolicy | Permission granters |

### Execution Chain (Unchanged)
```
Skill.capabilities
    ↓
ToolRegistry.toolsForSkill(skill) → maps capabilities to Tool IDs
    ↓
TaskEngine.create(goal, role, riskClass, dependsOn)
    ↓
TaskEngine.run(taskId) → TaskEngine owns execution lifecycle
    ↓
ToolRegistry → ExecutionPolicy → PermissionBroker → AuditLog
    ↓
Actual tool execution (DeviceInfoTool, AppLaunchTool, etc.)
```

**No SkillOrchestrator in Core.** Workflow coordination is a higher-layer concern (can be built on top of TaskEngine + skill metadata).

---

## Deferred: Dependency Resolution

`Skill.dependencies` and `SkillManifest.dependencies` exist as **metadata only**. No resolution logic exists in Core.

Future work may add:
- Dependency declaration validation
- Cycle detection
- Version compatibility checking
- Activation ordering
- Missing dependency handling

Currently: **advisory metadata only**.

---

## Deferred: SkillOrchestrator

No SkillOrchestrator in Core. Can be built as a higher layer on:
- TaskEngine (execution authority)
- SkillRegistry (metadata)
- SkillManifest (contracts)
- PermissionBroker (approvals)

---

## Security Review

### Invariants Verified
- ✅ Skill never executes — no `execute()` method
- ✅ Skill capabilities → ToolRegistry → ExecutionPolicy → PermissionBroker → AuditLog
- ✅ Trust transitions `internal` — external adapters cannot call
- ✅ `SkillRegistry.enable()` requires `TRUSTED` + (`INSTALLED` or `DISABLED`)
- ✅ Manifest parsing never grants trust — `toUntrustedSkill()` always `UNTRUSTED`/`disabled`/`DISCOVERED`
- ✅ TaskEngine sole execution authority — no SkillOrchestrator in core
- ✅ No dependency resolver — `dependencies` is metadata only
- ✅ No skill-to-task execution engine — orchestration is higher-layer
- ✅ Fail-closed persistence — all mutations rollback on failure
- ✅ Artifact trust reset on `install()` — prevents silent upgrade attacks

### Android Compatibility
- `SkillRegistryAndroid` exposes: `register()`, `all()`, `get()`, `enable()`, `disable()`, `remove()`, `find()`
- New methods `install()`, `markUpdateAvailable()` are `public` (not exposed via Android facade)
- No Android API changes required

---

## Files Changed

| File | Change Type |
|------|-------------|
| `core/src/main/kotlin/org/aethra/core/skills/SkillRegistry.kt` | Added `install()`, `markUpdateAvailable()`; fixed `enable()`/`disable()` per contract |
| `core/src/test/kotlin/org/aethra/core/skills/SkillRegistryTest.kt` | Added comprehensive contract tests |
| `docs/architecture/SKILL_ORCHESTRATION.md` | Modified documentation |

**Protected files NOT modified:**
- `Skill.kt`, `SkillManifest.kt`, `SkillTrustState`, `SkillState`
- `TaskEngine.kt`, `PermissionBroker.kt`, `ExecutionPolicy.kt`, `ToolRegistry.kt`, `ExternalCapabilityRouter.kt`
- `.github/workflows/skill-core.yml`
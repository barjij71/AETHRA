# Role Contracts — AETHRA Development Roles and Boundaries

Defines the roles involved in AETHRA development, their boundaries, and how work is handed off between them. Used by the entry orchestrator and individual workflow SKILLs.

## Roles

### Agent (AI coding agent)
The primary actor. Loads only the workflow SKILL relevant to the current task. Follows the deterministic procedure in that SKILL. Does not load the entire repository context.

**Boundaries:**
- May not modify `docs/` product architecture decisions without a `review` workflow pass.
- May not weaken, skip, or delete tests.
- May not perform destructive Git operations.
- May not introduce JARVIS material, Hermes runtime, or mock implementations.
- May not commit credentials or API keys.

### Reviewer (human or agent in review mode)
Validates that a completed change meets the workflow's review gate. Checks CI status, test results, and architectural consistency.

**Boundaries:**
- Cannot merge or push; only approves or rejects.
- Cannot bypass CI requirements.

### Release operator
Triggers CI workflows, verifies APK artifacts, and confirms evidence collection.

**Boundaries:**
- Only acts on `codex/phase-c-handoff` after review gate passes.
- Does not force-push or rewrite history.

## Handoffs

| From | To | Trigger | Artifact |
|---|---|---|---|
| Entry orchestrator | Workflow SKILL | Task classified | Loaded workflow context |
| Workflow procedure | Review gate | All procedure steps done | Changed files + tests |
| Review gate | Release operator | All gate items pass | Green CI + clean tree |
| Release operator | Entry orchestrator | CI green on `origin/codex/phase-c-handoff` | Ready for next task |

## Boundaries between development and runtime

- **Development orchestration** (this system): organizes how an AI coding agent analyzes, implements, verifies, reviews, and commits AETHRA changes.
- **Runtime execution** (AETHRA Core TaskEngine): executes application tasks on the device.
- These systems are separate. A development workflow SKILL must never be confused with a runtime component.

## References
- `SKILL.md` — entry orchestrator
- `reference/routing-matrix.md` — task → workflow mapping

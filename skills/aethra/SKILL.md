# AETHRA Development Workflow System

## Goal

Route repository development tasks to the correct scoped workflow while preserving AETHRA's current architecture and repository boundaries.

## Canonical repository

Repository: `barjij71/AETHRA`

Current development branch: `feature/phase-7-tool-execution-contract`

Do not redirect agents to historical branches, Windows copies, Instabox workspaces, or obsolete repositories.

## Invariants

- Inspect the current repository before editing.
- Never discard local work to match a remote branch.
- Do not use reset --hard, git clean, rebase, force-push, or history rewriting.
- Do not weaken, skip, or delete tests to make CI green.
- Do not reintroduce Jarvis material or make Hermes the AETHRA runtime.
- Do not invent new architecture without a concrete consumer and source evidence.
- Every change must be focused and leave the working tree clean after verification.
- Report CI/test results honestly.

## Procedure

1. Verify pwd, branch, HEAD, remote, and working-tree status.
2. Inspect relevant source and tests read-only before editing.
3. Identify the owning architectural boundary.
4. Make the smallest justified change.
5. Add or update regression tests when behavior changes.
6. Run required verification.
7. Review the diff for unintended changes.
8. Create one focused conventional commit.
9. Push the intended branch normally.
10. Re-verify local HEAD equals the pushed remote HEAD.

## Architecture boundaries

- TaskEngine is the authoritative Task execution authority.
- WorkflowEngine orchestrates workflows but does not replace TaskEngine.
- SkillOrchestrator is the Skill-to-Task boundary.
- Capability availability and user permission are separate concepts.
- ExecutionPolicy and PermissionBroker remain part of authorization.
- TaskVerifier remains verification authority.
- Android-specific implementations belong in app/; Core remains platform-neutral.
- Provider contracts may exist without a production provider implementation.
- ExecutionMode is runner-local unless a future contract proves a broader scope.

## Phase discipline

A new phase must not be created merely to continue numbering.

Before proposing implementation:

1. perform read-only contract discovery;
2. prove a concrete production consumer or architectural gap;
3. define the smallest contract required;
4. obtain acceptance of the contract;
5. implement only that contract;
6. verify with tests/build evidence.

If no gap is proven, report:

NO NEW PHASE REQUIRED

## Review gate

- [ ] Correct repository and branch verified.
- [ ] Current source inspected before changes.
- [ ] Scope limited to accepted task.
- [ ] Required tests pass.
- [ ] No destructive Git operation used.
- [ ] Diff contains no unrelated changes.
- [ ] Working tree clean.
- [ ] Remote branch verified after push.

## References

- docs/architecture/DECISIONS.md
- docs/roadmap/PHASES.md
- docs/security/EXECUTION_MODEL.md
- docs/AETHRA_PROGRESS.md
- docs/BUILD_ROADMAP.md

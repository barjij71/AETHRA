# AETHRA Development Workflow System

## Goal
Route development tasks to the correct scoped workflow contract so that future coding agents work on AETHRA consistently, safely, and incrementally — loading only the context relevant to the current task.

## Scope
Applies to all development work on the AETHRA repository (`barjij71/AETHRA`, branch `codex/phase-c-handoff`): core Kotlin, Android app, CI workflows, and this workflow system itself. Does not cover runtime execution (that is AETHRA Core's TaskEngine) or product design decisions already captured in `docs/`.

## Triggers
- Any intent to modify, add, or delete code in the AETHRA repository
- Intent phrases: "implement", "fix", "refactor", "add", "remove", "test", "review", "release", "build", "CI"
- File patterns: anything under `core/src/`, `app/src/`, `.github/workflows/`, `skills/`

## Inputs
- The task description (what needs to be done)
- The current repository state (HEAD commit, working tree status)
- Relevant AETHRA docs (`docs/architecture/DECISIONS.md`, `docs/roadmap/PHASES.md`, `docs/security/EXECUTION_MODEL.md`)

## Invariants
- GitHub AETHRA (`origin/codex/phase-c-handoff`) is the authoritative source; local clones are not sources of truth
- No destructive Git operations: `reset --hard`, `git clean`, `rebase`, `force push`, history rewriting
- Do not weaken, skip, or delete tests to make CI green
- Do not reintroduce JARVIS material; do not make Hermes the runtime; do not add mock implementations
- Every change must be a focused, additive commit; working tree must be clean before and after
- CI must be green before considering work complete

## Procedure
1. **Classify the task** — read `reference/routing-matrix.md` to determine which workflow(s) apply
2. **Load only the relevant workflow SKILL** — `skills/aethra/workflows/<name>/SKILL.md`; do not load all workflows at once
3. **Inspect first** — read the current repository files referenced by the workflow before making changes
4. **Follow the procedure** — execute the deterministic steps in the loaded workflow SKILL
5. **Verify** — run the tests the workflow requires; for Android changes, confirm CI produces a green APK
6. **Review gate** — confirm every item in the workflow's review gate passes
7. **Commit** — single focused commit with a conventional message (`fix(scope): ...`, `feat(scope): ...`, `test(scope): ...`)
8. **Push** — normal push to `codex/phase-c-handoff`; confirm CI is green

## Outputs
- Focused, committing changes to AETHRA source
- Updated or new tests covering the change
- Green CI on `codex/phase-c-handoff`
- Clean working tree

## Review gate
- [ ] Working tree is clean before starting
- [ ] Only the relevant workflow SKILL was loaded (progressive disclosure)
- [ ] Current repository state was inspected before changes
- [ ] All required tests pass locally
- [ ] CI is green on `origin/codex/phase-c-handoff` after push
- [ ] Commit is focused and atomic (one logical change)
- [ ] No tests were weakened, skipped, or deleted

## References
- `skills/aethra/reference/routing-matrix.md` — task → workflow mapping
- `skills/aethra/reference/role-contracts.md` — roles, boundaries, handoffs
- `docs/architecture/DECISIONS.md` — AETHRA architecture decisions
- `docs/roadmap/PHASES.md` — delivery roadmap
- `docs/security/EXECUTION_MODEL.md` — security and execution model
- `docs/AETHRA_PROGRESS.md` — project progress and evidence

# Workflow: review

## Goal
Perform architecture and design review of AETHRA changes: verify architectural consistency, decision alignment, and review gate compliance before release.

## Scope
Applies to `docs/architecture/DECISIONS.md`, proposed changes to core architecture, and cross-cutting changes that affect multiple workflows. Does not cover routine bug fixes within a single workflow's scope.

## Triggers
- File patterns: `docs/architecture/**`, changes affecting multiple workflow domains
- Intent phrases: "architecture decision", "design review", "review gate", "architectural consistency", "A-001 through A-010"

## Inputs
- `docs/architecture/DECISIONS.md` (A-001 through A-010 decisions)
- Proposed change description and diff
- Relevant workflow SKILLs for the affected domains
- CI results (Core Contract Tests, Experimental APK)

## Invariants
- Do not redesign AETHRA into another product
- Do not reintroduce JARVIS material
- Do not make Hermes the AETHRA runtime
- Do not introduce mock implementations
- External capabilities remain integrations/adapters, not replacements for Core
- GitHub is the authoritative source; local clones are not sources of truth

## Procedure
1. Read `docs/architecture/DECISIONS.md` for relevant decisions
2. Review the proposed change against each applicable decision
3. Check that all affected workflow SKILLs' review gates pass
4. Verify CI is green on `origin/codex/phase-c-handoff`
5. Confirm no architectural drift (no redesign, no mock, no Hermes/JARVIS)
6. Document the review outcome
7. Commit if changes are needed to docs or workflow SKILLs

## Outputs
- Review outcome document (pass/fail with rationale)
- Optional updates to `docs/architecture/DECISIONS.md` or workflow SKILLs
- Clean working tree

## Review gate
- [ ] Change aligns with A-001 through A-010 architecture decisions
- [ ] No redesign of AETHRA product direction
- [ ] No JARVIS, Hermes, or mock implementations
- [ ] External capabilities remain adapters, not Core replacements
- [ ] All affected workflow review gates pass
- [ ] CI green on `origin/codex/phase-c-handoff`

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `docs/architecture/DECISIONS.md` — architecture decisions
- `docs/roadmap/PHASES.md` — delivery roadmap

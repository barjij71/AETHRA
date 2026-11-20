# Workflow: release

## Goal
Manage releases, CI evidence collection, APK artifact verification, and release readiness for AETHRA.

## Scope
Applies to `.github/workflows/`, CI run verification, APK artifact checks, test evidence collection, and release readiness. Does not cover core code changes.

## Triggers
- File patterns: `.github/workflows/**`, CI run results, APK artifacts
- Intent phrases: "release", "CI green", "APK artifact", "evidence", "release readiness"

## Inputs
- GitHub Actions runs for `codex/phase-c-handoff`
- CI run conclusions (Core Contract Tests, Experimental APK)
- APK artifact (`aethra-experimental-debug-apk`): expired=False, size_in_bytes > 0
- Test result XMLs under `core/build/test-results/test/`
- Core test count (must preserve 101+ tests)

## Invariants
- APK success may only be claimed from actual GitHub Actions runs
- Do not fabricate APK results
- Do not claim an APK is successful unless the actual GitHub Actions workflow confirms it
- Core test suite must preserve full coverage (101+ tests, 0 failures)
- All workflow steps must conclude `success`
- Working tree must be clean before release

## Procedure
1. After pushing changes, wait for CI auto-trigger on `codex/phase-c-handoff`
2. Check `Experimental APK` and `Core Contract Tests` runs
3. Verify `RUN_CONCLUSION=success` for both
4. Verify APK artifact: not expired, size > 0
5. Verify core test count: 101+ tests, 0 failures
6. Confirm all job steps concluded `success`
7. Document release evidence (commit SHA, CI run IDs, test results, artifact status)
8. Working tree must be clean

## Outputs
- Release evidence document (commit SHA, run IDs, test results, artifact status)
- Green CI on `origin/codex/phase-c-handoff`
- Clean working tree

## Review gate
- [ ] Core Contract Tests: success
- [ ] Experimental APK: success (if Android changes)
- [ ] APK artifact not expired, size > 0 (if Android changes)
- [ ] Core tests: 101+ tests, 0 failures
- [ ] All job steps: success
- [ ] Working tree: clean

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `skills/aethra/workflows/github/SKILL.md` — GitHub/CI (related)

# Workflow: github

## Goal
Manage GitHub Actions workflows, CI integration, APK artifact verification, and release evidence for AETHRA.

## Scope
Applies to `.github/workflows/` (android.yml, core-tests.yml, experimental-apk.yml), CI triggers, APK artifact validation, and workflow triggers. Does not cover core code changes (those use their respective workflows).

## Triggers
- File patterns: `.github/workflows/**`
- Intent phrases: "CI", "workflow", "GitHub Actions", "assembleDebug", "APK artifact", "core contract tests", "run core tests"

## Inputs
- `.github/workflows/core-tests.yml` — runs `:core:test` on pushes to `codex/phase-c-handoff`
- `.github/workflows/experimental-apk.yml` — builds `:app:assembleDebug`, uploads `aethra-experimental-debug-apk`
- `.github/workflows/android.yml` — Android build/test/lint
- GitHub Actions API (public read access for run status, annotations, artifacts)
- APK artifact name: `aethra-experimental-debug-apk`

## Invariants
- CI must be green before considering work complete
- APK success may only be claimed from actual GitHub Actions runs; do not fabricate results
- Do not claim an APK is successful unless the actual GitHub Actions workflow confirms it
- Workflow triggers: `push` to `codex/phase-c-handoff`, `workflow_dispatch`
- APK artifact must not be expired; verify `expired=False` and size > 0
- All workflow steps must conclude `success` (not just the job)

## Procedure
1. After pushing changes to `codex/phase-c-handoff`, wait for CI auto-trigger
2. Check GitHub Actions page for `Experimental APK` and `Core Contract Tests` runs
3. Verify `RUN_CONCLUSION=success` for both
4. Verify APK artifact: `expired=False`, `size_in_bytes > 0`
5. Verify all steps in the job concluded `success`
6. If any step fails, diagnose and fix before proceeding
7. Do not trigger an APK build unless Android code changes require it or there is a meaningful milestone

## Outputs
- Green CI runs on `origin/codex/phase-c-handoff`
- Valid APK artifact (when Android changes were made)
- Clean working tree

## Review gate
- [ ] Core Contract Tests run conclusion: success
- [ ] Experimental APK run conclusion: success (if Android changes)
- [ ] APK artifact not expired, size > 0 (if Android changes)
- [ ] All job steps concluded `success`
- [ ] CI green on `origin/codex/phase-c-handoff`

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `skills/aethra/workflows/release/SKILL.md` — release evidence (related)

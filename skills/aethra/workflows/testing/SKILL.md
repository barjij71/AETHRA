# Workflow: testing

## Goal
Define and maintain AETHRA's test patterns: contract tests, deterministic tests, and test conventions for Core and Android.

## Scope
Applies to `core/src/test/kotlin/org/aethra/core/**` and `app/src/test/java/org/aethra/assistant/**`. Covers test creation, test execution, and test verification. Does not cover runtime test execution on devices.

## Triggers
- File patterns: `core/src/test/**`, `app/src/test/**`
- Intent phrases: "contract test", "unit test", "deterministic test", "add test", "test coverage", "flaky test"

## Inputs
- Gradle wrapper (`gradle-8.13`, `gradlew.bat`)
- `JAVA_HOME` (Microsoft OpenJDK 17)
- Core test command: `:core:test --rerun --no-daemon --console=plain`
- Test result XMLs under `core/build/test-results/test/TEST-*.xml`
- JUnit 4, kotlinx.coroutines test support

## Invariants
- Core test suite must preserve full coverage (101+ tests, 0 failures)
- `--rerun` bypasses Gradle cache so tests actually execute
- No test may be weakened, skipped, or deleted to make CI green
- Flaky tests must be reproducible before fixing; do not fix unreproducible flakes speculatively
- Tests must be deterministic (no timing-dependent assertions without polling)
- Java/gradle daemon file locks must be cleared between runs if needed

## Procedure
1. Inspect existing tests under `core/src/test/kotlin/org/aethra/core/`
2. Add a deterministic contract test for the changed behavior
3. Run `:core:test --rerun --no-daemon --console=plain` locally
4. Verify `TESTS=N FAILURES=0` from XML results
5. Confirm review gate
6. Commit `test(core): ...` or `test(android): ...`
7. Push; confirm CI Core Contract Tests green

## Outputs
- New or updated test file(s)
- Green CI Core Contract Tests run
- Clean working tree

## Review gate
- [ ] `:core:test --rerun` passes locally with 0 failures
- [ ] Test is deterministic (uses polling, not fixed sleeps where timing matters)
- [ ] No existing test weakened or removed
- [ ] Test covers real behavior, not mock implementation
- [ ] CI green on `origin/codex/phase-c-handoff`

## References
- `skills/aethra/SKILL.md` — entry orchestrator
- `skills/aethra/workflows/core/SKILL.md` — core task engine tests (related)
- `docs/roadmap/PHASES.md` — Phase 2 exit criteria (orchestration and recovery tests pass)

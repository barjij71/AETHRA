# Delivery Roadmap

This roadmap separates architecture decisions from implemented and verified capabilities. No phase is complete without evidence attached to the delivery report.

## Phase 0 — Repository and architecture foundation
- [x] Confirm the AETHRA repository was empty before initialization.
- [x] Add project overview and initial architecture decisions.
- [x] Record first-pass open-source comparison and integration boundaries.
- [ ] Audit licenses and transitive dependencies for the selected components.
- [ ] Confirm application package structure, build toolchain, and supported Android versions.

## Phase 1 — Minimal Android buildable shell
- [ ] Create a native Android project (Kotlin + Jetpack Compose is the leading candidate).
- [ ] Add Gradle wrapper and reproducible CI build.
- [ ] Add a minimal home/chat shell, navigation, RTL-ready layouts, and accessibility basics.
- [ ] Add unit-test infrastructure and a build/test evidence report.
- [ ] Do not add root or Linux execution yet.

Exit criteria: clean checkout builds in CI; tests pass; debug APK is generated; no secrets or large model downloads are required.

## Phase 2 — Independent AETHRA Core
- [ ] Define task, event, tool, permission, provider, skill, and agent contracts.
- [ ] Implement persisted task state, checkpoints, bounded retries, cancellation, and recovery.
- [ ] Implement local memory with review/export/delete controls.
- [ ] Add structured audit records and evidence-based completion checks.
- [ ] Add fake/mock providers and deterministic tests.

Exit criteria: orchestration and recovery tests pass without any network model.

## Phase 3 — Model providers and agent orchestration
- [ ] Implement provider-neutral streaming/tool-call interface.
- [ ] Add one-provider baseline and safe fallback policy.
- [ ] Add reusable role-based agents and a persisted agent registry.
- [ ] Add adaptive selection based on capability, privacy, availability, and cost.
- [ ] Add code-generated agent proposal, isolated tests, review, and explicit activation.
- [ ] Prove retries and reviewer loops are bounded.

Exit criteria: an end-to-end mocked task runs through plan → execute → test → repair → verify with traceable evidence.

## Phase 4 — Android integration and permission broker
- [ ] Add least-privilege Android tools using supported public APIs.
- [ ] Add an explicit permission broker for privileged operations.
- [ ] Add logs/diagnostics integrations such as logcat/dumpsys only where permitted.
- [ ] Separate read-only diagnostics from mutations.
- [ ] Add approval and cancellation UI for sensitive actions.

Exit criteria: permission tests demonstrate that agents cannot bypass the broker.

## Phase 5 — Linux Runtime
- [ ] Compare userland implementations and distribution choices.
- [ ] Prototype isolated execution and process lifecycle management.
- [ ] Prototype root/chroot integration separately on the target device.
- [ ] Apply resource limits, storage quotas, process cleanup, logs, and update verification.
- [ ] Verify Android/ARM64 compatibility, SELinux constraints, and restart behavior.

Exit criteria: test scripts prove both execution modes and document their real isolation/security limits.

## Phase 6 — REA and reverse-engineering lab
- [ ] Pin a verified REA release and inspect its license/dependency inventory.
- [ ] Validate Node.js/npm availability in the selected Linux runtime.
- [ ] Test MCP and CLI invocation from AETHRA's adapter.
- [ ] Check which analysis engines can run locally on Android/ARM64.
- [ ] Add APK/static inspection, runtime evidence collection where authorized, and report generation.
- [ ] Do not require commercial engines for core operation.

Exit criteria: a known test target produces a reproducible report with evidence and explicit unknowns.

## Phase 7 — Skills, plugins, and agent extension lifecycle
- [ ] Define a manifest, source provenance, hashes, versioning, license, dependencies, and requested permissions.
- [ ] Download and scan from configured sources.
- [ ] Require user approval before first execution of each new skill.
- [ ] Test skill execution in a constrained environment.
- [ ] Add enable/disable/remove, audit history, and rollback.

## Phase 8 — Voice, holographic presence, and files
- [ ] Evaluate offline/online speech recognition and synthesis for Arabic, Moroccan Darija, and English.
- [ ] Implement interruptible speech and wake-word behavior within Android background limits.
- [ ] Add file intake for images, PDF, text, ZIP, APK and other supported formats with type detection.
- [ ] Add lightweight female holographic presence with power/performance-aware degradation.

## Phase 9 — Hardening and optional Windows bridge
- [ ] Security review, regression tests, battery/thermal/performance tests, and recovery tests.
- [ ] Verify license notices and supply-chain provenance.
- [ ] Consider optional Windows pairing/bridge only after Android core is stable.
- [ ] Publish build evidence, known limitations, and a user-facing installation guide.

## Operating rule

Do not mark a task complete from an agent's assertion alone. Require command output, test results, or reproducible manual evidence. If blocked, preserve state and report the exact blocker.

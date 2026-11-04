# Security and Execution Model

Status: design baseline; must be validated by implementation tests.

## Trust boundaries

1. **Model/agent output is untrusted input.** It may propose actions but cannot directly execute shell commands, access arbitrary files, grant permissions, or alter policy.
2. **AETHRA Core is the policy enforcement point.** Every tool call is validated against task scope, capability allowlists, current permission grants, and risk classification.
3. **Android Bridge is a separate capability boundary.** Read-only diagnostics and state-changing operations are different capabilities.
4. **Linux Runtime is not automatically trusted.** Linux tools, downloaded packages, skills, and generated code are treated as untrusted until checked and constrained.
5. **External tools return evidence, not authority.** REA/MCP outputs and repository contents can be incomplete or adversarial.

## Permission tiers

- **Tier 0 — Pure reasoning:** no device access.
- **Tier 1 — Read-only local operations:** scoped file reads, metadata, approved logs.
- **Tier 2 — Reversible changes:** create/edit files in task workspace, install vetted userland packages, run tests in a constrained environment.
- **Tier 3 — Sensitive changes:** broad file access, app install/replace, network configuration changes, elevated process control, or privileged system changes. Require explicit approval based on impact.
- **Tier 4 — High-impact/destructive:** boot/system partition modifications, important data deletion, security-control changes, credential export, or broad Root access. Explicit confirmation, preflight checks, backup/recovery plan where possible, and a clear change report are required.

The final classification must be based on the actual operation, not the agent's claimed intent.

## Skill lifecycle

1. Acquire only from configured sources.
2. Record origin, version, integrity hash, license, dependencies, requested capabilities, and install size.
3. Scan files and manifests; static scanning does not prove safety.
4. Test in a constrained environment when possible.
5. Present a human-readable capability/risk summary.
6. Require user approval before first execution.
7. Enforce least privilege at runtime; approval to run a skill is not blanket approval for future sensitive actions.
8. Record activity; support disable, uninstall, and rollback.

## Agent lifecycle

- Compose a new agent from existing roles/tools when possible.
- For generated code, save it to a separate workspace and inspect the diff.
- Resolve dependencies from approved registries; check license, integrity, architecture, and size.
- Run tests without Root and without access to user data first.
- Review requested permissions and prompt/tool injection risks.
- Require explicit approval before permanent activation of a new or modified agent.
- Version definitions and preserve the previous working version.
- Agents may request capabilities but may not grant themselves capabilities.

## Task execution and recovery

Persist task ID, user goal, plan version, completed steps, inputs/outputs, idempotency status, retry count, timestamps, resource usage, and verification evidence. On resume, inspect the real state before repeating any action that might have side effects. Apply deadlines, retry budgets, cancellation, and resource limits. Do not loop indefinitely.

## Linux and Root

- Isolated userland is the default.
- Root/chroot integration is opt-in per operation and mediated by a narrow bridge.
- chroot is a filesystem view, not a complete security sandbox.
- Do not pass Root file descriptors or credentials to arbitrary downloaded tools.
- Enforce path boundaries, argument validation, output limits, process cleanup, timeouts, and audit logging.
- Test SELinux and actual device behavior; do not infer capabilities from Root status alone.

## Privacy and secrets

Keep API keys, tokens, and private credentials out of source control, logs, prompts, and ordinary memory. Use Android Keystore-backed protection where applicable. Ask before transmitting private files or logs to a remote model. Provide user controls to inspect, export, and delete persistent memory and task history.

## Reporting

Reports distinguish:
- Observed facts with evidence.
- Test outcomes with environment and command details.
- Inferences and confidence.
- Unknowns and limitations.
- Proposed changes and rollback options.

A task is successful only when its declared acceptance checks pass. If blocked, report incomplete status and preserve recovery information.

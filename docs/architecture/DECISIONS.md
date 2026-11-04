# Architecture Decisions (Draft)

Status: accepted design direction; implementation not yet verified.

## A-001 — Independent AETHRA Core

AETHRA owns task planning and state, memory, permission checks, skills/tools, provider selection, agent orchestration, execution monitoring, and result verification. External agents and frameworks are adapters, not runtime prerequisites.

## A-002 — Android-first native application

Build the primary user experience as a native Android app. Windows integration is optional and later. The initial implementation language/UI framework should be confirmed during the bootstrap spike; Kotlin + Jetpack Compose is the leading candidate because the product requires deep Android integration.

## A-003 — Hybrid Linux runtime

Support two execution modes:
1. **Isolated userland by default:** tools run in a managed Linux userland with restricted access.
2. **Privileged Android integration:** a separately mediated bridge may use root and chroot/bind mounts where compatible with Android, SELinux, filesystem layout, and device policy.

A chroot is not, by itself, a security sandbox. Root must not be inherited by every tool. The core mediates each privileged operation.

## A-004 — REA as an optional analysis integration

Use the official `morluto/rea` project as the planned reverse-engineering tool provider through MCP/CLI integration. The REA skill instructions, MCP server, and external analysis engines are distinct deliverables. The core remains usable when REA is unavailable.

REA's current documented requirements include supported Node.js versions and npm; additional analysis engines are target-specific. Android/ARM64 compatibility and the required engines must be proven in a spike before promising on-device support.

## A-005 — Agent orchestration owned by AETHRA

Use a provider-neutral orchestration contract with persisted task state, role definitions, tool allowlists, bounded retries, cancellation, evidence records, and explicit approval gates. Start with a small internal state machine rather than embedding a large Python framework in the Android process. Evaluate LangGraph/CrewAI as optional Linux-side adapters only if a measurable requirement justifies them.

Agents may be created in two ways:
- Compose a role from existing instructions, skills, tools, and permissions.
- Generate new code when existing components are insufficient.

New agent definitions and code must be versioned, tested in an isolated environment, reviewed, and explicitly approved for persistent activation. Agents cannot grant themselves permissions.

## A-006 — Adaptive AI providers

Allow one model to serve several roles by default. Use multiple providers/models only when task complexity, independent review, privacy, availability, or measured quality warrants it. Support local inference where practical and optional cloud providers. Never imply that all hosted APIs are free or unlimited.

## A-007 — Autonomous task execution with bounded safety

Execute ordinary, authorized steps without repeatedly interrupting the user. Persist checkpoints and verify outcomes using observable evidence. Ask for explicit approval before destructive or high-impact operations, including sensitive root actions, boot/system partition changes, important data deletion, broad permission expansion, or disclosure of private data.

Use bounded retries, time/resource limits, cancellation, logs, and safe recovery. If a dependency, permission, or physical interaction prevents completion, preserve progress and report the blocker honestly.

## A-008 — Skills and extensions

Download and inspect skills automatically only from configured trusted sources. Verify source, integrity, license, dependencies, compatibility, and requested permissions. Require user approval before first execution of each newly installed skill. A successful scan is not a guarantee of safety.

## A-009 — Local-first memory and task state

Store task plans, checkpoints, tool results, and user-controlled memory locally by default. Provide review, correction, export, and deletion controls. Keep secrets separate from conversational memory and never commit credentials.

## A-010 — Evidence-based delivery

A feature is complete only when supported by build/test output or an explicitly documented manual test. Keep architecture decisions, dependency/license inventory, known limitations, and a delivery report up to date.

## Decisions still open

- Exact Android application/module structure and minimum supported Android version.
- Linux userland implementation and lifecycle approach (PRoot/chroot/root bridge, storage, updates, resource limits).
- Whether an on-device LLM is viable on the target device for selected workloads.
- Voice/wake-word engine and model licensing/size.
- Which external components can be reused directly after complete license and dependency review.

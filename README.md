# AETHRA

**AETHRA is an independent, Android-first personal agent platform.**

The project is being designed around an independent core that owns task execution, persistent memory, permissions, skills/tools, provider selection, multi-agent orchestration, and result verification. Open-source projects may be integrated as optional components after compatibility, security, and license review; no external agent framework is required for the core to operate.

## Current status

**Phase 0 — architecture and repository foundation.** This repository was initially empty. Current work is focused on recording architecture decisions, comparing candidate projects, defining safety boundaries, and establishing verifiable implementation milestones. No Android build or runtime feature is claimed to exist yet.

## Product principles

- Android-native application first; Windows integration is a later, optional capability.
- Independent AETHRA Core; Hermes, REA, and other external agents/tools are integrations, not the core.
- Local-first memory and task state; cloud AI providers are optional and replaceable.
- Linux userland managed by AETHRA, with an isolated default mode and a separately controlled rooted integration mode where device policy permits.
- Autonomous task execution with checkpoints, evidence-based verification, bounded retries, and explicit approval for sensitive actions.
- Extensible skills and agents with source, license, compatibility, permission, and version checks.
- No mandatory paid API or subscription for core operation; hosted model usage may have provider-specific costs and limits.
- No claims of completion without reproducible build/test evidence.

## Documentation

- [Architecture decisions](docs/architecture/DECISIONS.md)
- [Open-source project comparison](docs/research/PROJECT_COMPARISON.md)
- [Delivery roadmap](docs/roadmap/PHASES.md)
- [Security and execution model](docs/security/EXECUTION_MODEL.md)

## Development status

Implementation has not yet started. The documents above describe intended architecture and planned work, not shipped capabilities.

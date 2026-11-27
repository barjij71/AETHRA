# AETHRA Build Roadmap

## Purpose

This document describes the current Kotlin/Android AETHRA repository. It does not describe the historical Jarvis/Tauri migration plan.

## Current architecture

AETHRA is an Android-first Personal AI Execution Environment with a platform-neutral Kotlin/JVM Core.

- `core` — platform-neutral Kotlin/JVM execution and domain contracts.
- `app` — Android runtime, platform adapters, UI, Android capability implementations, and Android-specific integration.

## Verified foundation

The following contracts are already implemented and verified:

1. Skill Runtime lifecycle
2. Task verification and evidence
3. Tool execution capability contract
4. Tool authorization/security ordering
5. Runner-local execution modes
6. Capability availability boundary
7. Skill-to-Task provenance
8. Workflow orchestration exposure through Runtime/CoreRegistry

Agent execution and production Provider/Model integration are intentionally not declared as completed.

## Development order

Do not assign a new numbered implementation phase solely because a previous phase has finished.

Before starting any new phase:

1. inspect the complete current repository;
2. identify a concrete production consumer or contract gap;
3. prove the gap from source;
4. define the smallest boundary needed;
5. implement only after the contract is accepted;
6. add regression tests;
7. verify Core and Android integration as applicable;
8. commit and push a focused change.

## Android build

The Android module is a native Kotlin + Jetpack Compose application.

Do not use historical Tauri/Node/Rust build instructions. Current Android build instructions must come from the Gradle project and CI workflow actually present in the repository.

## Historical work

Older migration/roadmap material may exist in Git history. Historical documents must not be treated as current implementation status. If a historical document is retained, it must be explicitly labeled historical and must not direct agents to obsolete branches, repositories, or runtimes.

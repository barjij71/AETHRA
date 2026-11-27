# AETHRA Architecture Decisions

**Status:** accepted architecture baseline; implementation status is tracked separately in the progress/phase documents.

## A-001 — Independent AETHRA Core

AETHRA owns task state and execution orchestration, memory, policy/permission checks, skills/tools, provider selection, agent lifecycle, workflow orchestration, and result verification.

## A-002 — Android-first native application

The primary user experience is a native Android app using Kotlin + Jetpack Compose. Windows/Linux integrations are optional future platform boundaries.

## A-003 — Capability-mediated platform operations

Platform-specific operations are exposed to Core through narrow interfaces such as capability availability and runtime/task-runner boundaries. Core does not import Android APIs.

## A-004 — Optional external analysis integrations

External analysis tools such as REA may be integrated through explicit adapters when a concrete product requirement exists. Their availability must not be assumed by Core.

## A-005 — Agent lifecycle and execution

Agent definitions and lifecycle are owned by Core. An Agent does not automatically require a separate execution engine; existing TaskEngine/TaskRunner infrastructure remains the execution authority unless a concrete requirement proves otherwise.

## A-006 — Provider/model neutrality

Core defines provider-neutral contracts and routing. Concrete model providers are platform integrations. A provider implementation, credential store, and network transport are not required until a production consumer requires model inference.

## A-007 — Autonomous execution with bounded safety

Ordinary authorized operations may execute without repeated interactive prompts. Sensitive/high-impact operations remain subject to capability, policy, permission, verification, persistence, cancellation, retry, and resource limits.

## A-008 — Skills and extensions

Skills are inspected and validated according to source, integrity, license, dependencies, compatibility, and requested capabilities. Approval to install/execute a skill is not blanket authorization for future sensitive operations.

## A-009 — Local-first memory and task state

Task state, checkpoints, tool results, and user-controlled memory are local-first where supported. Secrets remain separate from ordinary conversational memory.

## A-010 — Evidence-based delivery

A feature is complete only when supported by source evidence and appropriate tests/build results. Documentation must distinguish verified implementation from planned work and unknowns.

## Decisions intentionally open

- exact minimum supported Android API/device matrix;
- concrete Linux runtime implementation;
- on-device LLM viability;
- voice/wake-word implementation and licensing;
- optional external integrations and their license/dependency review.

These open product/platform questions are not automatically architectural defects.

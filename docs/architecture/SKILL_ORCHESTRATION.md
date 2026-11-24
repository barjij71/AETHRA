# AETHRA Skill Orchestration

## Authority boundary

AETHRA Core owns skill identity, contract validation, trust, activation, workflow orchestration, permissions, evidence, postconditions, and final outcome status.

External sources such as `SKILL.md`, MCP servers, Hermes, model providers, or remote metadata are adapters only. They cannot grant trust, enable a skill, approve permissions, or declare a workflow successful.

## Skill manifest contract

Current Core contract version: **1.0**.

Required JSON fields:
- `contractVersion`
- `id`
- `name`
- `version`

Optional fields:
- `entrypoints`
- `capabilities`
- `requestedPermissions`
- `dependencies`
- `platforms`
- `riskClass`

Unknown fields, unsupported contract versions, malformed arrays, blank required values, and unsupported risk classes are rejected.

Parsing is validation only. `SkillRegistry.registerManifest()` is the Core-owned ingestion boundary: conversion to a Core Skill always produces an **UNTRUSTED, disabled, DISCOVERED** skill before persistence. Trust and activation remain Core-owned operations.

## Workflow acceptance boundary

A future three-skill integration test must prove:
1. typed/schema-valid artifact handoff;
2. independent skills run in parallel when dependencies allow;
3. dependent skills block after required predecessor failure;
4. invalid manifest/artifact is rejected;
5. restart leaves uncertain non-idempotent work as `UNKNOWN_OUTCOME`;
6. exact approval binds actor, risk, operation, target, capability, requestId and payload hash;
7. final success requires evidence and postcondition verification.

## External adapters

`SKILL.md` and MCP imports are untrusted by default. They may be parsed and mapped to Core manifests, but never inherit trust or permissions from the source document or server.

## Android lifecycle

Durable workflow state must survive process death. Android scheduling/resource limits are execution concerns; they must not move policy authority out of Core.

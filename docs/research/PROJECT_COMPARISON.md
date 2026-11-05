# Open-Source Project Comparison

Date reviewed: 2026-11-04. This is a first-pass architecture comparison based on public repository documentation and selected repository files. It is not a complete source audit, dependency audit, security audit, or legal opinion. Re-check exact revisions and all transitive licenses before copying code or shipping binaries.

## Summary

| Project | Useful capability | License noted in repository | Proposed use | Main limitation / follow-up |
|---|---|---|---|---|
| [Gimi](https://github.com/pony-huang/Gimi) | Native Kotlin/Compose Android assistant; phone tools, memory, background tasks, MCP, A2A agents, skills and APK plugins | Apache-2.0 | Primary reference for Android UX and extension concepts | Requires a user-supplied model API key; do not copy the app wholesale. Audit dependencies and implementation before reuse. |
| [Synapse](https://github.com/cannibal3004/Synapse) | Android assistant with hosted or on-device inference, tools, scheduled tasks, speech, memory and attachments | License file was not found at the checked root path; verify repository/license metadata and file headers before reuse | Reference for local inference isolation and tool UX | Uses Termux for its general shell integration; AETHRA must manage its own Linux runtime rather than require Termux. |
| [Hermes Mobile](https://github.com/Hy4ri/hermes-mobile) | Native Kotlin/Compose client for managing an existing Hermes dashboard, skills, plugins, providers, jobs and logs | Apache-2.0 | Reference for agent administration UX and gateway integration patterns | It is a Hermes client and needs a reachable Hermes dashboard; Hermes is not a prerequisite for AETHRA. |
| [REA](https://github.com/morluto/rea) | MCP/CLI reverse engineering across applications, binaries and runtime evidence | MIT for REA-authored code; third-party materials retain their own terms | Optional analysis engine integrated through an AETHRA adapter | Documented Node.js/npm requirements; native analysis may require external engines, some commercial. ARM64/Android support must be proven. |
| [Hermes Vox](https://github.com/chezgoulet/hermes-vox) | Android voice client, local speech stack, interruption/barge-in, animated GPU presence | GPL-3.0 | Study voice pipeline and visual-performance techniques | It is a Hermes client, marked beta/work-in-progress; copyleft and third-party license obligations need review before code reuse. |
| [NectarLink](https://github.com/thtbee/nectarlink) | Local encrypted Android/Windows pairing, file transfer, clipboard, phone/PC integration | Mixed: core libraries MPL-2.0; apps include GPL-3.0-or-later plus project-specific additional permission; docs CC-BY-4.0 | Optional future PC-link architecture reference | Review each component's license and trademark policy; do not import the whole project. |
| [LangGraph](https://github.com/langchain-ai/langgraph) | Stateful orchestration, durable execution, checkpoints and human-in-the-loop | MIT | Reference for workflow semantics and persistence | Python/JS framework, not an Android-native core. Use only if an adapter is justified. |
| [CrewAI](https://github.com/crewAIInc/crewAI) | Role-based multi-agent crews and event-driven flows | MIT (verify exact release and transitive dependencies before shipping) | Reference for agent-role design and delegation | Python-centric; not a reason to make AETHRA dependent on a large framework. |
| [Ollama](https://github.com/ollama/ollama) | Local model serving on compatible host platforms | MIT for Ollama repository; model weights have separate licenses | Optional host/local-model adapter | Do not assume desktop server behavior or all models fit Android memory/thermal limits. |
| [Hermes Agent](https://github.com/NousResearch/hermes-agent) | Existing agent capabilities, skills, memory, tools and automation | Review repository license and all dependencies at the chosen revision before reuse | Optional integration/reference only | AETHRA Core must remain functional without it. |

## What to build ourselves

AETHRA should own the components that define its identity and safety boundary:
- Task state machine, durable checkpoints, retry/timeout/cancel policy and success verification.
- Permission broker and privileged Android bridge.
- Memory lifecycle and user controls.
- Agent registry, versioning, role composition, code-agent review/approval.
- Provider-neutral model interface and adaptive routing.
- Skill registry, source/integrity/license checks, first-run approval and permission scopes.
- Linux runtime manager and tool lifecycle.
- Unified audit trail and final evidence report.

## What to integrate behind adapters

- REA for reverse engineering, where its runtime and target requirements are met.
- MCP tools and selected skill packages after validation.
- Optional local/cloud model providers.
- Voice/STT/TTS components after device-performance and license tests.
- Optional desktop-link implementation after component-level license review.

## Initial decision

Do not embed Gimi, Synapse, Hermes Mobile, LangGraph, or CrewAI as the AETHRA core. Reuse ideas and isolated components only after checking code provenance, compatibility, transitive dependencies, security, and licenses. Keep the first Android implementation small and independently testable.

## Evidence links

- Gimi README and LICENSE: https://github.com/pony-huang/Gimi
- Synapse README: https://github.com/cannibal3004/Synapse
- Hermes Mobile README and LICENSE: https://github.com/Hy4ri/hermes-mobile
- REA README and LICENSE: https://github.com/morluto/rea
- Hermes Vox README and LICENSE: https://github.com/chezgoulet/hermes-vox
- NectarLink README and LICENSE: https://github.com/thtbee/nectarlink
- LangGraph README: https://github.com/langchain-ai/langgraph
- CrewAI README: https://github.com/crewAIInc/crewAI
- Ollama README: https://github.com/ollama/ollama

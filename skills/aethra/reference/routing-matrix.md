# Routing Matrix — Task → Workflow Mapping

Use this matrix to classify a development task and load the correct workflow SKILL.
Progressive disclosure: load only the workflow for the current task, not all workflows.

| Task category | File patterns | Intent phrases | Workflow SKILL |
|---|---|---|---|
| Core task engine | `core/src/main/kotlin/org/aethra/core/task/**` | task engine, execution, start, cancel, resume, retry, claim, slot | `workflows/core/SKILL.md` |
| Task lifecycle & scheduling | `core/src/main/kotlin/org/aethra/core/task/**` | create, enqueue, drain, queue, state transition | `workflows/tasks/SKILL.md` |
| Task dependencies | `core/src/main/kotlin/org/aethra/core/task/**` | dependsOn, dependency chain, wake-up, approval gate | `workflows/dependencies/SKILL.md` |
| Memory | `core/src/main/kotlin/org/aethra/core/memory/**`, `app/src/main/java/**/memory/**` | MemoryEngine, persist, store, retrieve, reload | `workflows/memory/SKILL.md` |
| Providers / models | `core/src/main/kotlin/org/aethra/core/providers/**` | provider, model, router, routing decision, fallback | `workflows/providers/SKILL.md` |
| Tools / skills | `core/src/main/kotlin/org/aethra/core/tools/**`, `core/src/main/kotlin/org/aethra/core/skills/**`, `app/src/main/java/**/tools/**`, `app/src/main/java/**/skills/**` | tool registry, native tool, skill registration, tool dispatch | `workflows/tools/SKILL.md` |
| Policy / permissions | `core/src/main/kotlin/org/aethra/core/security/**`, `app/src/main/java/**/security/**`, `app/src/main/java/**/permissions/**` | permission broker, policy engine, risk class, approval | `workflows/policy/SKILL.md` |
| Android runtime | `app/src/main/java/**/runtime/**`, `app/src/main/java/**/adapters/**` | Runtime, PlatformAdapters, Lifecycle, CoreRegistry, bind | `workflows/android-runtime/SKILL.md` |
| Android UI | `app/src/main/java/org/aethra/assistant/**` | AthraHomePage, MainActivity, Compose, task/event display | `workflows/android-ui/SKILL.md` |
| External capabilities | `core/src/main/kotlin/org/aethra/core/external/**` | ExternalCapability, capability router, external adapter | `workflows/external/SKILL.md` |
| GitHub / CI | `.github/workflows/**` | workflow, CI, GitHub Actions, APK artifact, assembleDebug | `workflows/github/SKILL.md` |
| MCP integration | any | MCP server, MCP client, MCP integration | `workflows/mcp/SKILL.md` |
| InstaBox / remote computer | `core/src/main/kotlin/org/aethra/core/external/**` | InstaBox, transport, machine lifecycle, remote exec, API key | `workflows/instabox/SKILL.md` |
| Security | `docs/security/**`, `core/src/main/kotlin/org/aethra/core/security/**` | trust boundary, permission tier, audit, execution model | `workflows/security/SKILL.md` |
| Testing | `core/src/test/**`, `app/src/test/**` | contract test, unit test, test pattern, deterministic test | `workflows/testing/SKILL.md` |
| Architecture review | `docs/architecture/**`, any | design decision, architecture change, review gate | `workflows/review/SKILL.md` |
| Release / CI evidence | `.github/workflows/**`, APK artifacts, test reports | release, CI green, APK build, evidence, artifact | `workflows/release/SKILL.md` |

## Classification rules
- If a task matches multiple workflows, load the most specific one first, then hand off to the next.
- If unsure, start with `workflows/core/SKILL.md` or `workflows/review/SKILL.md`.
- Never load more than two workflows in a single session step.

## References
- `SKILL.md` — entry orchestrator
- `reference/role-contracts.md` — roles, boundaries, handoffs

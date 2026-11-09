# AETHRA

**AETHRA is an independent, Android-first personal agent platform.**

AETHRA = Personal AI Execution Environment:
Core + Agents + Skills + Tools + Memory + Workflows + Providers + Policy +
Verification + Device Mesh + Voice + Holographic UX.

## Current status

**Phase 0 ??? Core foundation implemented.**

- CoreRegistry: registre des composants
- CoreRuntime: moteur d'ex??cution et cycle de vie
- TaskEngine: machine ?? ??tats, d??pendances, retry, idempotence
- PermissionBroker: politiques SAFE/LOW/SENSITIVE/DESTRUCTIVE
- EventBus: bus d'??v??nements avec historique
- MemoryEngine: m??moires layer??es (conversation, working, task, etc.)
- VerificationEngine: evidence, postconditions, REA model
- Workflow: DAG, ordonnancement topologique, d??tection de cycles
- PersistenceManager: sauvegarde/restauration d'??tat
- OpenCode provider: adaptateur pour OpenCode sur instabox

## Repository structure

```
core/          - Noyau AETHRA (runtime, task, policy, memory, etc.)
agents/        - Agents autonomes
skills/        - Skills installables et v??rifiables
tools/         - Outils externes
providers/     - Providers de mod??les (OpenAI, Ollama, OpenCode, etc.)
devices/       - Device Mesh (Android, Windows, Linux)
ui/            - Interface Chat, Terminal, Holographique
voice/         - STT, TTS, Wake Word
config/        - Configuration
tests/         - Tests unitaires, int??gration, E2E
docs/          - Documentation d'architecture
```

## Development roadmap (from AETHRA guidance)

1. Core actuel et points d'int??gration (fait)
2. V??rification TaskEngine C1/C2 avec tests r??els
3. Correction des bugs production avant modification des tests
4. Workflow/DAG/concurrency/persistence/recovery
5. Skill runtime et v??rification/permissions
6. Verification/Evidence/Postcondition dans l'ex??cution de t??che
7. Device Mesh comme couche Runtime/Capability
8. Policy/Permission pour chaque device capability et Root
9. Provider/Model routing et local AI
10. Interface Hermes-style: Chat + Terminal + streaming + tool cards
11. Persona holographique et voice adaptative
12. Surfaces attachment/memory/device/skill
13. Tests ind??pendants puis int??gration
14. Preuve de build/test avant d??claration d'ach??vement

## Principes produits

- Android-first ; Windows/Linux comme capacit??s optionnelles
- Noyau AETHRA ind??pendant ; Hermes, REA, etc. sont des int??grations, pas le c??ur
- M??moire et ??tat de t??che locaux d'abord ; providers cloud optionnels et rempla??ables
- Ex??cution autonome avec points de contr??le, v??rification par evidence, approbation explicite
- Aucun abonnement payant obligatoire pour le c??ur ; les providers h??berg??s peuvent avoir des co??ts

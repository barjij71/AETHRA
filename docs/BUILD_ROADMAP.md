# Build Roadmap

## Objective
Migrate AETHRA from `barjij71/jarvis` (branch `feature/aethra-identity`) to `barjij71/aethra` on `main` and establish a stable, testable, reproducible build pipeline for the Android voice assistant.

## Phased Approach

| Phase | Focus | Dependencies | Acceptance Criteria |
|-------|-------|--------------|---------------------|
| **Phase A – Stability & Buildability** | Ensure the new repository is buildable and CI passes. Install dependencies, verify TypeScript, Rust/Android toolchain (if needed). | Node 24, npm, Rust toolchain, Android SDK/NDK (for device builds), GitHub Actions runner | All CI steps succeed on GitHub Actions for `main` and `feature/build-phase-a`. |
| **Phase B – Conversation & Memory** | Implement conversation tracking, message log, memory store/retrieval, cancellation/retry. | Phase A | Unit tests pass; memory persists across restarts. |
| **Phase C – AI Providers** | Add/modify AI providers, test connectivity, error handling, fallback logic, protect API keys. | Phase A | Provider tests pass; automatic fallback works. |
| **Phase D – Attachments & Files** | File selection, preview, removal, upload; size/extension/MIME validation. | Phase C | Attachments flow works; unsupported files rejected. |
| **Phase E – Skills & Plugins** | Unified registry, registration, activation, permissions, uninstallation. | Phase D | Tests cover install/update/fail/rollback. |
| **Phase F – Voice & Wake Word** | Replace dummy STT/TTS, verify wake word detection, handle Android background/notification/battery limits. | Phase A + C | Voice commands recognized on device; wake word detection verified. |
| **Phase G – UI & UX** | Polish AETHRA interface, RTL/Arabic, accessibility, keep brand. | Phase B, D, E, F | UI builds and passes lint; RTL works. |
| **Phase H – Remote PC Integration** | Expose PC control via remote PC app; permission prompts, logging. | Phase A + E | Integration tests pass; no shell access. |

## Build Commands (web bundle)

From repository root:

```bash
# install dependencies (inside jarvis-studio-gui)
cd jarvis-studio-gui
npm ci
npm run typecheck
npm run test
npm run lint
npm run build
```

## Android (debug) Build

```bash
cd jarvis-studio-gui
# Requires ANDROID_HOME, NDK_HOME, Rust toolchain
npx tauri android build --debug --apk --target aarch64
```

## Notes

- The Rust Tauri backend is compiled only when Android build is triggered.
- CI should only run web bundle steps to keep builds fast.
- Never commit secrets; use GitHub Secrets for signing keys and API keys.
- Acceptable changes only after review (PR).

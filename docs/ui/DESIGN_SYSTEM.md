# AETHRA Android UI System

## Direction
AETHRA uses a native Kotlin + Jetpack Compose interface. The visual language is a calm, premium holographic assistant rather than a dense sci-fi control panel. Dark navy surfaces, restrained cyan/violet accents, readable Arabic typography, and purposeful motion are the defaults.

## Navigation
- Four primary destinations: Home, Tasks, Tools, Settings.
- Users can move between destinations with horizontal paging or by tapping the persistent bottom navigation dock.
- Important home actions link to the same destinations; there are no decorative dead-end controls.
- Page selection follows the pager state so swipe and tap navigation remain synchronized.

## Design tokens
- Background: `#070B14`
- Surface: `#111A2A`
- Primary cyan: `#67E8F9`
- Secondary violet: `#9B8CFF`
- Muted text: `#9AAAC0`
- Cards use consistent rounded corners, subtle borders, and limited glow.
- Text hierarchy and spacing should be shared instead of redefined per feature.

## Holographic presence
The first implementation uses a small Compose Canvas orb with simple rings and a low-frequency pulse. It is an interface prototype, not a generated 3D avatar or a real voice-state visualizer. Future animation must reflect real app states (listening, speaking, executing, approval required) and must stop or reduce when the screen is inactive, reduced motion is enabled, power saving is active, or thermal conditions require it.

## Tool and task surfaces
All integrations should reuse common task/tool cards that can display:
- truthful lifecycle state and last-updated time;
- progress only when the underlying task reports measurable progress;
- source, version, requested permissions, and review status for skills;
- logs, result evidence, errors, cancellation, and retry when supported.

A UI status must never imply that a backend tool is working when it is only planned. Privileged actions must go through the core permission broker, never directly from a composable.

## Accessibility and localization
- Arabic-first copy and RTL layout.
- Preserve readable contrast and sufficiently large touch targets.
- Keep tap navigation available even when swipe navigation exists.
- Support English and reduced-motion preferences as implementation proceeds.

## Performance rules
- Prefer Compose primitives and Canvas for simple graphics.
- Avoid a permanently running animation outside the visible foreground screen.
- Keep visual state separate from task execution state.
- Profile release builds on real devices before adding heavier shader, blur, or 3D effects.
- Add UI tests for navigation, RTL, accessibility semantics, and state rendering.

## Current limitations
This is the initial navigation and visual shell only. Task execution, provider selection, skill installation, Linux, REA, voice, persistent memory, and permission brokerage are not implemented by this UI commit.

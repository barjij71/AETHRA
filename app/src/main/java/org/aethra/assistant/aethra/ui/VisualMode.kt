package org.aethra.assistant.aethra.ui

/**
 * Android-side visual performance mode.
 *
 * Consumed by the avatar and other visual components to reduce or disable
 * expensive visual effects when the device is in power-saving mode.
 *
 * This is purely an Android UI concern. It does not alter Core task execution,
 * battery-related Core behavior, or any Core contract.
 */
enum class VisualMode {
    /** Normal/balanced mode with full visual effects and animation. */
    BALANCED,
    /** Power-saving/degraded mode that reduces or disables expensive effects. */
    POWER_SAVING
}

/**
 * Simple state holder for the current visual mode.
 *
 * In a future phase this can be replaced by a ViewModel or persisted state.
 * For now it holds the mode in memory for the lifetime of the process.
 */
class VisualModeState {
    private var mode: VisualMode = VisualMode.BALANCED

    val current: VisualMode
        get() = mode

    fun setMode(newMode: VisualMode) {
        mode = newMode
    }

    fun isPowerSaving(): Boolean = mode == VisualMode.POWER_SAVING
    fun animationsEnabled(): Boolean = mode == VisualMode.BALANCED
}

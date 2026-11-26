package org.aethra.core.tools

/**
 * Provides the capability authority for the current execution environment.
 *
 * This interface answers the question: "Does this environment provide capability X?"
 * It is used to validate that tool.requiredCapabilities can be satisfied before
 * authorization and execution.
 *
 * Trust boundary: The execution environment implements this to declare what it
 * actually provides. A Tool, Model, or Provider may NOT self-authorize - the
 * environment must be the trusted source of capability availability.
 *
 * Core must remain platform-neutral - Android, Termux, Windows, and other
 * environments provide their own implementations.
 */
interface CapabilityAvailability {
    /**
     * Check if the execution environment provides the named capability.
     *
     * @param name the capability identifier (e.g., "file.read", "clipboard.read")
     * @return true if the environment provides this capability, false otherwise
     */
    fun hasCapability(name: String): Boolean
}
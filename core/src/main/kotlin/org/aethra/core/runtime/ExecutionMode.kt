package org.aethra.core.runtime

/**
 * User-selectable execution mode that influences interactive permission requirements.
 *
 * Modes:
 * - ASK: Conservative. All operations requiring approval go through PermissionBroker.
 * - STANDARD: Normal/default. Safe operations execute without interactive approval;
 *             operations classified as requiring approval use PermissionBroker.
 * - FULL_ACCESS: User explicitly chooses less restrictive mode for otherwise-authorized
 *                eligible operations. Does NOT bypass security boundaries.
 *
 * IMPORTANT: FULL_ACCESS MUST NOT mean "bypass all security". It only changes whether
 * interactive approval is required for operations that are otherwise authorized and
 * eligible for the selected mode.
 *
 * FULL_ACCESS will NOT bypass:
 * - ExecutionPolicy
 * - Capability checks
 * - Tool authorization
 * - Risk restrictions
 * - TaskEngine authority
 * - TaskVerifier authority
 */
enum class ExecutionMode {
    ASK,
    STANDARD,
    FULL_ACCESS;

    companion object {
        /** Default mode when no saved preference exists or preference is invalid */
        const val DEFAULT = "STANDARD"

        /** Key for persistent storage */
        const val PREFERENCE_KEY = "execution_mode"

        /** Parses from storage string, returns DEFAULT for invalid values */
        fun fromStorageString(value: String?): ExecutionMode = when (value?.uppercase()) {
            "ASK" -> ASK
            "FULL_ACCESS" -> FULL_ACCESS
            "STANDARD" -> STANDARD
            else -> STANDARD
        }
    }

    /** Returns the string representation for storage */
    fun toStorageString(): String = this.name
}
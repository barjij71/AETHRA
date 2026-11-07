package org.aethra.core.skills

import org.aethra.core.domain.RiskClass

/**
 * Legal transitions for the skill lifecycle. Every transition is validated
 * before being applied. Callers that request an illegal transition receive false.
 *
 * States:
 *   DISCOVERED   - Skill metadata exists locally (not yet validated)
 *   VALIDATING   - Integrity and manifest checks in progress
 *   VALID        - Passed validation, ready to install
 *   INSTALLING   - Package preparation/verification in progress
 *   INSTALLED    - Installed and ready for approval/execution
 *   UPDATING     - Update in progress
 *   UPDATE_AVAILABLE - A newer version is available
 *   DISABLED     - Installed but disabled (cannot execute)
 *   FAILED       - Validation or installation failed (transient)
 *   REMOVED      - Uninstalled
 */
object SkillLifecycle {
    private val allowed = mapOf(
        SkillState.DISCOVERED to setOf(SkillState.VALIDATING),
        SkillState.VALIDATING to setOf(SkillState.VALID, SkillState.FAILED),
        SkillState.VALID to setOf(SkillState.INSTALLING),
        SkillState.INSTALLING to setOf(SkillState.INSTALLED, SkillState.FAILED),
        SkillState.INSTALLED to setOf(SkillState.UPDATE_AVAILABLE, SkillState.DISABLED, SkillState.REMOVED),
        SkillState.UPDATE_AVAILABLE to setOf(SkillState.UPDATING),
        SkillState.UPDATING to setOf(SkillState.INSTALLED, SkillState.FAILED),
        SkillState.DISABLED to setOf(SkillState.INSTALLED, SkillState.REMOVED),
        SkillState.FAILED to setOf(SkillState.VALIDATING, SkillState.REMOVED),
        SkillState.REMOVED to emptySet()
    )

    /** Returns true if the transition from [from] to [to] is legal. */
    fun canTransition(from: SkillState, to: SkillState): Boolean =
        allowed[from]?.contains(to) == true

    /** Returns the next state if the transition is legal, otherwise returns [from]. */
    fun next(from: SkillState, to: SkillState): SkillState =
        if (canTransition(from, to)) to else from
}

/** Risk classification for skills. The classification does not grant authority. */
enum class SkillRisk {
    LOW, MEDIUM, HIGH, CRITICAL
}

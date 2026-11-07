package org.aethra.core.skills

import org.junit.Assert.*
import org.junit.Test

/** Tests for the skill lifecycle state machine. */
class SkillLifecycleTest {
    @Test
    fun discoveredToValidatingIsLegal() {
        assertTrue(SkillLifecycle.canTransition(SkillState.DISCOVERED, SkillState.VALIDATING))
    }

    @Test
    fun discoveredToInstalledIsIllegal() {
        assertFalse(SkillLifecycle.canTransition(SkillState.DISCOVERED, SkillState.INSTALLED))
    }

    @Test
    fun validatingToFailedIsLegal() {
        assertTrue(SkillLifecycle.canTransition(SkillState.VALIDATING, SkillState.FAILED))
    }

    @Test
    fun failedToRemovedIsLegal() {
        assertTrue(SkillLifecycle.canTransition(SkillState.FAILED, SkillState.REMOVED))
    }

    @Test
    fun removedFromInstalledIsLegal() {
        assertTrue(SkillLifecycle.canTransition(SkillState.INSTALLED, SkillState.REMOVED))
    }

    @Test
    fun removedFromDisabledIsLegal() {
        assertTrue(SkillLifecycle.canTransition(SkillState.DISABLED, SkillState.REMOVED))
    }

    @Test
    fun disabledFromInstalledIsLegal() {
        assertTrue(SkillLifecycle.canTransition(SkillState.INSTALLED, SkillState.DISABLED))
    }

    @Test
    fun updateFlow() {
        assertTrue(SkillLifecycle.canTransition(SkillState.INSTALLED, SkillState.UPDATE_AVAILABLE))
        assertTrue(SkillLifecycle.canTransition(SkillState.UPDATE_AVAILABLE, SkillState.UPDATING))
        assertTrue(SkillLifecycle.canTransition(SkillState.UPDATING, SkillState.INSTALLED))
    }

    @Test
    fun nextReturnsNextState() {
        assertEquals(SkillState.VALIDATING, SkillLifecycle.next(SkillState.DISCOVERED, SkillState.VALIDATING))
        assertEquals(SkillState.DISCOVERED, SkillLifecycle.next(SkillState.DISCOVERED, SkillState.INSTALLED))
    }
}

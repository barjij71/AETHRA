package org.aethra.core.skills

import org.junit.Assert.*
import org.junit.Test

class SkillManifestContractTest {
    @Test fun validJsonParsesAndCannotSelfActivate() {
        val result = SkillManifest.parse("""{"contractVersion":"1.0","id":"demo.skill","name":"Demo","version":"1.2.0","capabilities":["demo.read"],"riskClass":"READ_ONLY"}""")
        assertTrue(result.isSuccess)
        val skill = result.getOrThrow().toUntrustedSkill()
        assertEquals(SkillTrustState.UNTRUSTED, skill.trustState)
        assertFalse(skill.enabled)
    }

    @Test fun unknownFieldIsRejected() {
        assertTrue(SkillManifest.parse("""{"contractVersion":"1.0","id":"demo","name":"Demo","version":"1.0.0","unexpected":true}""").isFailure)
    }

    @Test fun unsupportedVersionAndMalformedValuesAreRejected() {
        assertTrue(SkillManifest.parse("""{"contractVersion":"9.0","id":"demo","name":"Demo","version":"1.0.0"}""").isFailure)
        assertTrue(SkillManifest.parse("""{"contractVersion":"1.0","id":"demo","name":"Demo","version":"1.0.0","capabilities":"x"}""").isFailure)
        assertTrue(SkillManifest.parse("""{"contractVersion":"1.0","id":"demo","name":"Demo","version":"1.0.0","riskClass":"ROOT"}""").isFailure)
    }

    @Test fun missingOrBlankRequiredFieldsAreRejected() {
        assertTrue(SkillManifest.parse("""{"contractVersion":"1.0","name":"Demo","version":"1.0.0"}""").isFailure)
        assertTrue(SkillManifest.parse("""{"contractVersion":"1.0","id":"","name":"Demo","version":"1.0.0"}""").isFailure)
    }
}

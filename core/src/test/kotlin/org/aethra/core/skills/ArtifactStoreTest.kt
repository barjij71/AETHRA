package org.aethra.core.skills

import org.junit.Assert.*
import org.junit.Test

class ArtifactStoreTest {
    private fun registry() = ArtifactSchemaRegistry().also {
        assertTrue(it.register(ArtifactSchema("demo.text", "1.0", requiredFields = listOf("text"))))
    }

    @Test fun acceptsSchemaValidTypedArtifact() {
        val store = ArtifactStore(registry())
        val a = SkillArtifact("a1","demo.text","1.0","skill.a","1.0.0", """{"text":"hello"}""")
        assertTrue(store.put(a).isSuccess)
        assertEquals("skill.a", store.require("a1","demo.text","1.0").producerSkillId)
    }

    @Test fun rejectsUnknownSchema() {
        val store = ArtifactStore(registry())
        val a = SkillArtifact("a2","missing","1.0","skill.a","1.0.0", """{"text":"hello"}""")
        assertTrue(store.put(a).isFailure)
        assertNull(store.get("a2"))
    }

    @Test fun rejectsInvalidPayloadOrMissingRequiredField() {
        val store = ArtifactStore(registry())
        val malformed = SkillArtifact("a3","demo.text","1.0","skill.a","1.0.0", """{"text":""")
        assertTrue(store.put(malformed).isFailure)
        val missing = SkillArtifact("a4","demo.text","1.0","skill.a","1.0.0", """{"other":"value"}""")
        assertTrue(store.put(missing).isFailure)
    }

    @Test fun duplicateArtifactIdIsRejectedWithoutReplacement() {
        val store = ArtifactStore(registry())
        val first = SkillArtifact("same","demo.text","1.0","skill.a","1.0.0", """{"text":"first"}""")
        val second = first.copy(producerSkillId = "skill.b", payload = """{"text":"second"}""")
        assertTrue(store.put(first).isSuccess)
        assertTrue(store.put(second).isFailure)
        assertEquals("skill.a", store.get("same")!!.producerSkillId)
    }
}

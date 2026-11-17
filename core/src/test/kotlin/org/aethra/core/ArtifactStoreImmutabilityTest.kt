package org.aethra.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.aethra.core.orchestration.Artifact
import org.aethra.core.orchestration.ArtifactStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class ArtifactStoreImmutabilityTest {
    private fun artifact(
        id: String = "artifact-1",
        value: Any = mapOf("n" to 7),
        provenance: String = "test"
    ) = Artifact(
        id = id,
        schemaVersion = "1",
        producerSkillId = "producer",
        producerVersion = "1.0.0",
        taskId = "task-1",
        value = value,
        provenance = provenance,
        createdAt = 1000L
    )

    @Test
    fun reAddingIdenticalArtifactIsIdempotent() {
        val store = ArtifactStore()
        val original = artifact()
        store.put(original)
        store.put(original.copy())

        assertEquals(original, store.get(original.id))
    }

    @Test
    fun reusingArtifactIdForDifferentPayloadIsRejected() {
        val store = ArtifactStore()
        store.put(artifact())

        assertFailsWith<IllegalArgumentException> {
            store.put(artifact(value = mapOf("n" to 8)))
        }
        assertEquals(7, (store.get("artifact-1")?.value as Map<*, *>)["n"])
    }

    @Test
    fun reusingArtifactIdForDifferentProvenanceIsRejected() {
        val store = ArtifactStore()
        store.put(artifact())

        assertFailsWith<IllegalArgumentException> {
            store.put(artifact(provenance = "forged"))
        }
    }

    @Test
    fun restoreCannotReplaceExistingArtifactWithDifferentContent() {
        val store = ArtifactStore()
        store.put(artifact())

        val conflicting = artifact(value = mapOf("n" to 9))
        val snapshot = mapOf(
            conflicting.id to org.aethra.core.orchestration.StoredArtifact(
                id = conflicting.id,
                schemaVersion = conflicting.schemaVersion,
                producerSkillId = conflicting.producerSkillId,
                producerVersion = conflicting.producerVersion,
                taskId = conflicting.taskId,
                payload = conflicting.value.toString().let { JsonPrimitive(it) },
                provenance = conflicting.provenance,
                createdAt = conflicting.createdAt
            )
        )

        assertFailsWith<IllegalArgumentException> {
            store.restore("task-1", snapshot)
        }
    }
}

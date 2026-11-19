package org.aethra.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.aethra.core.orchestration.Artifact
import org.aethra.core.orchestration.ArtifactStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

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
    fun restoreOfEquivalentJsonPayloadIsIdempotentAcrossProcessSerialization() {
        val store = ArtifactStore()
        store.put(artifact())

        val original = artifact()
        val snapshot = mapOf(
            original.id to org.aethra.core.orchestration.StoredArtifact(
                id = original.id,
                schemaVersion = original.schemaVersion,
                producerSkillId = original.producerSkillId,
                producerVersion = original.producerVersion,
                taskId = original.taskId,
                payload = kotlinx.serialization.json.JsonObject(
                    mapOf("n" to JsonPrimitive(7))
                ),
                provenance = original.provenance,
                createdAt = original.createdAt
            )
        )

        store.restore("task-1", snapshot)

        val restored = store.get(original.id)
        assertEquals(original.schemaVersion, restored?.schemaVersion)
        assertEquals(original.producerSkillId, restored?.producerSkillId)
        assertEquals(original.producerVersion, restored?.producerVersion)
        assertEquals(original.taskId, restored?.taskId)
        assertEquals(original.provenance, restored?.provenance)
        val restoredPayload = assertNotNull(restored?.value as? JsonObject)
        assertEquals(JsonPrimitive(7), restoredPayload["n"])
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

package org.aethra.core.skills

import org.aethra.core.task.Task
import org.aethra.core.task.TaskRunner

/**
 * Core task-runner bridge for SkillExecutionEngine.
 *
 * TaskEngine still owns scheduling, retries, cancellation, persistence, and
 * lifecycle state. This runner only translates a Task's durable skill binding
 * into the existing Core SkillExecutionEngine contract.
 */
class SkillTaskRunner(
    private val skillEngine: SkillExecutionEngine,
    private val fallback: TaskRunner? = null,
    private val implementations: Map<String, SkillImplementation>
) : TaskRunner {
    override suspend fun run(task: Task): Result<Map<String, Any>> {
        val spec = task.skillExecutionSpec
            ?: return fallback?.run(task)
            ?: Result.failure(IllegalArgumentException("task has no skill execution spec"))

        val implementation = implementations[spec.entrypointId]
            ?: return Result.failure(
                IllegalArgumentException("no implementation registered for entrypoint: undefined")
            )

        return skillEngine.execute(spec.toInvocation(), implementation).map { artifact ->
            mapOf(
                "artifactId" to artifact.id,
                "schemaId" to artifact.schemaId,
                "schemaVersion" to artifact.schemaVersion
            )
        }
    }
}

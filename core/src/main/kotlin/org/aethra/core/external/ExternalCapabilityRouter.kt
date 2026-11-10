package org.aethra.core.external

/** Resolves an external capability by id and dispatches a structured operation. */
class ExternalCapabilityRouter(private val registry: ExternalCapabilityRegistry) {
    suspend fun execute(capabilityId: String, request: CapabilityRequest): Result<CapabilityResult> {
        val capability = registry.get(capabilityId)
            ?: return Result.failure(IllegalArgumentException("external capability not found: $capabilityId"))
        if (request.operation !in capability.capabilities) {
            return Result.failure(
                IllegalArgumentException("operation '${request.operation}' is not supported by $capabilityId")
            )
        }
        return capability.execute(request)
    }
}
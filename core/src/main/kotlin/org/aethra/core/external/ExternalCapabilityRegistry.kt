package org.aethra.core.external

/** Registry for optional external capabilities; Core never depends on provider implementations. */
class ExternalCapabilityRegistry {
    private val capabilities = LinkedHashMap<String, ExternalCapability>()

    @Synchronized
    fun register(capability: ExternalCapability) {
        require(capability.id.isNotBlank()) { "capability id must not be blank" }
        require(!capabilities.containsKey(capability.id)) {
            "external capability already registered: ${capability.id}"
        }
        capabilities[capability.id] = capability
    }

    @Synchronized fun unregister(id: String): ExternalCapability? = capabilities.remove(id)
    @Synchronized fun get(id: String): ExternalCapability? = capabilities[id]
    @Synchronized fun all(): List<ExternalCapability> = capabilities.values.toList()
    @Synchronized fun byType(type: ExternalCapabilityType): List<ExternalCapability> =
        capabilities.values.filter { it.type == type }
}
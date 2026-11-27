package org.aethra.assistant.aethra.providers

import android.content.Context
import org.aethra.core.providers.ProviderDefinition
import org.aethra.core.providers.ProviderFactory
import org.aethra.core.providers.LanguageModelProvider

/** Provider factory for [AndroidLanguageModelProvider].
 *
 * Creates provider instances from [ProviderDefinition] metadata. The API key
 * is supplied through [AndroidApiKeyStore] — never embedded in source or
 * provider definitions.
 */
class AndroidProviderFactory(
    private val apiKeyStore: AndroidApiKeyStore,
    private val endpoint: String,
    private val modelId: String
) : ProviderFactory {

    override fun create(providerDef: ProviderDefinition): LanguageModelProvider {
        return AndroidLanguageModelProvider(
            apiKeyStore = apiKeyStore,
            endpoint = providerDef.endpoint,
            modelId = providerDef.supportedModels.firstOrNull() ?: modelId
        )
    }
}

package org.koaks.agent.provider

import org.koaks.agent.credential.ApiKey
import org.koaks.agent.credential.CredentialRef

private const val OLLAMA_DEFAULT_BASE_URL = "http://localhost:11434/api/chat"
private const val OPENAI_DEFAULT_BASE_URL = "https://api.openai.com"
private const val QWEN_DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode"
private const val ANTHROPIC_DEFAULT_BASE_URL = "https://api.anthropic.com/v1/messages"
private const val OLLAMA_DEFAULT_MODEL = "llama3.1"
private const val OPENAI_DEFAULT_MODEL = "gpt-4.1-mini"
private const val QWEN_DEFAULT_MODEL = "qwen3.7-plus"
private const val ANTHROPIC_DEFAULT_MODEL = "claude-sonnet-4-20250514"

public enum class Provider private constructor(
    public val id: String,
    public val defaultBaseUrl: String,
    public val defaultModel: String,
) {
    OPENAI(
        id = "openai",
        defaultBaseUrl = OPENAI_DEFAULT_BASE_URL,
        defaultModel = OPENAI_DEFAULT_MODEL,
    ),
    QWEN(
        id = "qwen",
        defaultBaseUrl = QWEN_DEFAULT_BASE_URL,
        defaultModel = QWEN_DEFAULT_MODEL,
    ),
    ANTHROPIC(
        id = "anthropic",
        defaultBaseUrl = ANTHROPIC_DEFAULT_BASE_URL,
        defaultModel = ANTHROPIC_DEFAULT_MODEL,
    ),
    OLLAMA(
        id = "ollama",
        defaultBaseUrl = OLLAMA_DEFAULT_BASE_URL,
        defaultModel = OLLAMA_DEFAULT_MODEL,
    ),
    ;

    public companion object {
        public fun fromId(value: String): Provider? {
            val normalized = value.trim().lowercase()
            return entries.firstOrNull { it.id == normalized }
        }

        public fun idsForMessage(): String = entries.joinToString(", ") { it.id }
    }
}

public object ProviderCatalog {
    public fun infer(configuredProviders: List<Provider> = emptyList()): Provider = configuredProviders.firstOrNull() ?: Provider.QWEN
}

public data class ProviderProfile public constructor(
    public val provider: Provider,
    public val baseUrl: String,
    public val credentialRef: CredentialRef?,
    public val defaultModel: String,
    public val modelList: List<String>,
    public val apiKey: ApiKey? = null,
)

public data class ProviderProfiles public constructor(
    public val profiles: Map<Provider, ProviderProfile>,
    public val configuredProviders: List<Provider>,
) {
    public fun profileFor(provider: Provider): ProviderProfile =
        profiles[provider] ?: ProviderProfile(
            provider = provider,
            baseUrl = provider.defaultBaseUrl,
            credentialRef = null,
            defaultModel = provider.defaultModel,
            modelList = emptyList(),
            apiKey = null,
        )

    public fun availableProviders(): List<Provider> = configuredProviders.ifEmpty { Provider.entries.toList() }
}

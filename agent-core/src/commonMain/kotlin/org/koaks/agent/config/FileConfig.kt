package org.koaks.agent.config

public data class FileConfig public constructor(
    public val schemaVersion: Int? = null,
    public val defaultProvider: Provider? = null,
    public val defaultModel: String? = null,
    public val instructions: String? = null,
    public val threadId: String? = null,
    public val historyMessages: Int? = null,
    public val temperature: Double? = null,
    public val showReasoning: Boolean? = null,
    public val skillPaths: List<String> = emptyList(),
    public val skills: List<String> = emptyList(),
    public val providers: Map<Provider, FileProviderConfig> = emptyMap(),
    public val providerOrder: List<Provider> = emptyList(),
) {
    public companion object {
        public val Empty: FileConfig = FileConfig()
    }
}

public data class FileProviderConfig public constructor(
    public val baseUrl: String? = null,
    public val credentialRef: CredentialRef? = null,
    public val defaultModel: String? = null,
    public val modelList: List<String> = emptyList(),
    public val apiKey: ApiKey? = null,
) {
    public fun modelOrDefault(provider: Provider): String = defaultModel ?: modelList.firstOrNull() ?: provider.defaultModel
}

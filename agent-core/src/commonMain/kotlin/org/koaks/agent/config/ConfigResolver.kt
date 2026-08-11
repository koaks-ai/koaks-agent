package org.koaks.agent.config

public object ConfigResolver {
    public fun resolve(fileConfig: FileConfig): AgentConfig {
        if (fileConfig.schemaVersion != CURRENT_CONFIG_SCHEMA_VERSION) {
            throw CliException(
                "Unsupported config schema '${fileConfig.schemaVersion ?: "missing"}'. " +
                    "Run 'koaks init --force' to create schema $CURRENT_CONFIG_SCHEMA_VERSION.",
            )
        }
        val profiles = buildProfiles(fileConfig)
        val provider = fileConfig.defaultProvider ?: ProviderCatalog.infer(fileConfig.providerOrder)

        val profile = profiles.getValue(provider)

        val model = fileConfig.defaultModel ?: profile.defaultModel
        val history = fileConfig.historyMessages ?: DEFAULT_HISTORY_MESSAGES

        return AgentConfig(
            provider = provider,
            baseUrl = profile.baseUrl,
            credentialRef = profile.credentialRef,
            modelName = model,
            instructions = fileConfig.instructions ?: DEFAULT_INSTRUCTIONS.trim(),
            threadId = fileConfig.threadId ?: DEFAULT_THREAD_ID,
            historyMessages = history,
            temperature = fileConfig.temperature,
            showReasoning = fileConfig.showReasoning ?: false,
            skillPaths = fileConfig.skillPaths,
            skills = fileConfig.skills,
            providerProfiles = profiles,
            configuredProviders = fileConfig.providerOrder,
            apiKey = profile.apiKey,
        )
    }

    private fun buildProfiles(fileConfig: FileConfig): Map<Provider, ProviderProfile> =
        Provider.entries.associateWith { provider ->
            val providerConfig = fileConfig.providers[provider]
            ProviderProfile(
                provider = provider,
                baseUrl = providerConfig?.baseUrl ?: provider.defaultBaseUrl,
                credentialRef = providerConfig?.credentialRef,
                defaultModel = providerConfig?.modelOrDefault(provider) ?: provider.defaultModel,
                modelList = providerConfig?.modelList.orEmpty(),
                apiKey = providerConfig?.apiKey,
            )
        }
}

public const val CURRENT_CONFIG_SCHEMA_VERSION: Int = 1

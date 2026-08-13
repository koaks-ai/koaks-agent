package org.koaks.agent.config

import org.koaks.agent.definition.DEFAULT_INSTRUCTIONS
import org.koaks.agent.platform.Environment
import org.koaks.agent.provider.Provider
import org.koaks.agent.provider.ProviderCatalog
import org.koaks.agent.provider.ProviderProfile
import org.koaks.agent.provider.ProviderProfiles

public object ConfigResolver {
    /** Resolve the user's platform configuration file. */
    public fun resolve(environment: Environment): AgentConfig = resolve(ConfigFileLoader.load(environment))

    public fun resolve(fileConfig: FileConfig): AgentConfig {
        if (fileConfig.schemaVersion != CURRENT_CONFIG_SCHEMA_VERSION) {
            throw ConfigException(ConfigFailure.SchemaMismatch(fileConfig.schemaVersion, CURRENT_CONFIG_SCHEMA_VERSION))
        }
        val profiles = buildProfiles(fileConfig)
        val provider = fileConfig.defaultProvider ?: ProviderCatalog.infer(fileConfig.providerOrder)

        val profile = profiles.getValue(provider)

        val model = fileConfig.defaultModel ?: profile.defaultModel
        val history = fileConfig.historyMessages ?: DEFAULT_HISTORY_MESSAGES

        return AgentConfig(
            model =
                ModelDefaults(
                    provider = provider,
                    modelName = model,
                    temperature = fileConfig.temperature,
                    reasoningEnabled = fileConfig.showReasoning ?: false,
                ),
            providers = ProviderProfiles(profiles, fileConfig.providerOrder),
            prompt = PromptSettings(fileConfig.instructions ?: DEFAULT_INSTRUCTIONS.trim()),
            memory = MemorySettings(history),
            skills = SkillSettings(fileConfig.skillPaths, fileConfig.skills),
            session = SessionDefaults(fileConfig.threadId ?: DEFAULT_THREAD_ID),
        )
    }

    private fun buildProfiles(fileConfig: FileConfig): Map<Provider, ProviderProfile> =
        Provider.entries.associateWith { provider ->
            val providerConfig = fileConfig.providers[provider]
            ProviderProfile(
                provider = provider,
                baseUrl = providerConfig?.baseUrl ?: provider.defaultBaseUrl,
                defaultModel = providerConfig?.modelOrDefault(provider) ?: provider.defaultModel,
                modelList = providerConfig?.modelList.orEmpty(),
                apiKey = providerConfig?.apiKey,
            )
        }
}

public const val CURRENT_CONFIG_SCHEMA_VERSION: Int = 1

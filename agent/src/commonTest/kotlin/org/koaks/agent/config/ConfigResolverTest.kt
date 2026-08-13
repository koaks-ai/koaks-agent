package org.koaks.agent.config

import org.koaks.agent.credential.ApiKey
import org.koaks.agent.provider.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigResolverTest {
    @Test
    fun resolvesProviderWithoutMaterializingSecret() {
        val resolved =
            ConfigResolver.resolve(
                FileConfig(
                    schemaVersion = 1,
                    defaultProvider = Provider.OPENAI,
                    providers =
                        mapOf(
                            Provider.OPENAI to
                                FileProviderConfig(
                                    baseUrl = "https://example.test",
                                    defaultModel = "gpt-test",
                                    apiKey = ApiKey("secret"),
                                ),
                        ),
                    providerOrder = listOf(Provider.OPENAI),
                ),
            )

        assertEquals(
            "secret",
            resolved.providers
                .profileFor(Provider.OPENAI)
                .apiKey
                ?.value,
        )
        assertEquals("gpt-test", resolved.model.modelName)
    }

    @Test
    fun rejectsMissingSchemaVersionWithConfigFailure() {
        val error = assertFailsWith<ConfigException> { ConfigResolver.resolve(FileConfig.Empty) }

        assertEquals(ConfigFailure.SchemaMismatch(null, CURRENT_CONFIG_SCHEMA_VERSION), error.failure)
    }

    @Test
    fun preservesProviderConfigurationOrder() {
        val resolved =
            ConfigResolver.resolve(
                FileConfig(
                    schemaVersion = 1,
                    defaultProvider = Provider.OPENAI,
                    providers =
                        mapOf(
                            Provider.OPENAI to FileProviderConfig(defaultModel = "gpt-default"),
                            Provider.ANTHROPIC to FileProviderConfig(defaultModel = "claude-default"),
                        ),
                    providerOrder = listOf(Provider.OPENAI, Provider.ANTHROPIC),
                ),
            )

        assertEquals(listOf(Provider.OPENAI, Provider.ANTHROPIC), resolved.providers.availableProviders())
        assertEquals("claude-default", resolved.providers.profileFor(Provider.ANTHROPIC).defaultModel)
    }
}

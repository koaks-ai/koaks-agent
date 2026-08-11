package org.koaks.agent.config

import org.koaks.agent.credential.CredentialRef
import org.koaks.agent.credential.CredentialSource
import org.koaks.agent.provider.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigResolverTest {
    @Test
    fun resolvesProviderWithoutMaterializingSecret() {
        val credential = CredentialRef(CredentialSource.ENVIRONMENT, "OPENAI_API_KEY")
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
                                    credentialRef = credential,
                                    defaultModel = "gpt-test",
                                ),
                        ),
                    providerOrder = listOf(Provider.OPENAI),
                ),
            )

        assertEquals(credential, resolved.providers.profileFor(Provider.OPENAI).credentialRef)
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

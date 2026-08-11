package org.koaks.agent.config

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

        assertEquals(credential, resolved.credentialRef)
        assertEquals("gpt-test", resolved.modelName)
    }

    @Test
    fun rejectsMissingSchemaVersion() {
        assertFailsWith<CliException> { ConfigResolver.resolve(FileConfig.Empty) }
    }

    @Test
    fun appliesSessionPreferencesWithoutMutatingTheResolvedConfig() {
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

        val session = resolved.withPreferences(SessionPreferences(provider = Provider.ANTHROPIC))

        assertEquals(Provider.OPENAI, resolved.provider)
        assertEquals(Provider.ANTHROPIC, session.provider)
        assertEquals("claude-default", session.modelName)
    }
}

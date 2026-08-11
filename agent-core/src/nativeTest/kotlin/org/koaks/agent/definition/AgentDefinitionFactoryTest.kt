package org.koaks.agent.definition

import org.koaks.agent.config.AgentConfig
import org.koaks.agent.config.ApiKey
import org.koaks.agent.config.CliException
import org.koaks.agent.config.CredentialRef
import org.koaks.agent.config.CredentialSource
import org.koaks.agent.config.Provider
import org.koaks.agent.config.ProviderProfile
import org.koaks.agent.credential.CredentialResolver
import org.koaks.agent.provider.ProviderBinding
import org.koaks.agent.provider.ProviderRegistry
import org.koaks.agent.provider.ProviderSettings
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.loop.ModelSelection
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentDefinitionFactoryTest {
    @Test
    fun rejectsDuplicateProviderBindings() {
        val error =
            assertFailsWith<IllegalStateException> {
                ProviderRegistry
                    .Builder()
                    .register(FakeOpenAiBinding)
                    .register(FakeOpenAiBinding)
            }

        assertTrue(error.message.orEmpty().contains("openai"))
    }

    @Test
    fun rejectsUnavailableCredentialsBeforeBuildingAnAgent() {
        val config =
            testConfig(
                credentialRef = CredentialRef(CredentialSource.ENVIRONMENT, "MISSING_API_KEY"),
            )
        val factory = AgentDefinitionFactory(credentials = CredentialResolver { null })

        assertFailsWith<CliException> { factory.build(config) }
    }

    @Test
    fun acceptsPlaintextCredentialWithoutCallingTheResolver() {
        var resolverCalled = false
        val factory =
            AgentDefinitionFactory(
                credentials =
                    CredentialResolver {
                        resolverCalled = true
                        null
                    },
            )

        factory.build(testConfig(credentialRef = null, apiKey = ApiKey("secret"))).close()

        assertFalse(resolverCalled)
    }
}

private object FakeSettings : ProviderSettings

private object FakeOpenAiBinding : ProviderBinding<FakeSettings> {
    override val provider: Provider = Provider.OPENAI

    override fun settings(config: AgentConfig): FakeSettings = FakeSettings

    override fun ModelScope.select(
        settings: FakeSettings,
        credential: String,
    ): ModelSelection = error("Selection is not used by this registry contract test.")
}

private fun testConfig(
    credentialRef: CredentialRef?,
    apiKey: ApiKey? = null,
): AgentConfig {
    val profile =
        ProviderProfile(
            provider = Provider.OPENAI,
            baseUrl = Provider.OPENAI.defaultBaseUrl,
            credentialRef = credentialRef,
            defaultModel = Provider.OPENAI.defaultModel,
            modelList = emptyList(),
            apiKey = apiKey,
        )
    return AgentConfig(
        provider = Provider.OPENAI,
        baseUrl = profile.baseUrl,
        credentialRef = credentialRef,
        modelName = profile.defaultModel,
        instructions = "test",
        threadId = "test-thread",
        historyMessages = 8,
        temperature = null,
        showReasoning = false,
        skillPaths = emptyList(),
        skills = emptyList(),
        providerProfiles = mapOf(Provider.OPENAI to profile),
        configuredProviders = listOf(Provider.OPENAI),
        apiKey = apiKey,
    )
}

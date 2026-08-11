package org.koaks.agent.definition

import org.koaks.agent.credential.ApiKey
import org.koaks.agent.credential.CredentialRef
import org.koaks.agent.credential.CredentialResolver
import org.koaks.agent.credential.CredentialSource
import org.koaks.agent.provider.ModelRuntimeSettings
import org.koaks.agent.provider.Provider
import org.koaks.agent.provider.ProviderBinding
import org.koaks.agent.provider.ProviderRegistry
import org.koaks.agent.provider.ProviderSettings
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.loop.ModelSelection
import org.koaks.framework.memory.WindowMemoryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentDefinitionFactoryTest {
    @Test
    fun rejectsDuplicateProviderBindings() {
        val error =
            assertFailsWith<IllegalStateException> {
                ProviderRegistry.Builder().register(FakeOpenAiBinding).register(FakeOpenAiBinding)
            }

        assertTrue(error.message.orEmpty().contains("openai"))
    }

    @Test
    fun rejectsUnavailableCredentialsBeforeBuildingAnAgent() {
        val reference = CredentialRef(CredentialSource.ENVIRONMENT, "MISSING_API_KEY")
        val factory = AgentDefinitionFactory(credentials = CredentialResolver { null })

        val error =
            assertFailsWith<AgentSetupException> {
                factory.build(testSettings(credentialRef = reference), WindowMemoryProvider(8))
            }

        assertEquals(SetupFailure.CredentialUnavailable(Provider.OPENAI, reference), error.failure)
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

        factory.build(testSettings(credentialRef = null, apiKey = ApiKey("secret")), WindowMemoryProvider(8)).close()

        assertFalse(resolverCalled)
    }
}

private object FakeSettings : ProviderSettings

private object FakeOpenAiBinding : ProviderBinding<FakeSettings> {
    override val provider: Provider = Provider.OPENAI

    override fun settings(model: ModelRuntimeSettings): FakeSettings = FakeSettings

    override fun ModelScope.select(
        settings: FakeSettings,
        credential: String,
    ): ModelSelection = error("Selection is not used by this registry contract test.")
}

private fun testSettings(
    credentialRef: CredentialRef?,
    apiKey: ApiKey? = null,
): EffectiveAgentSettings =
    EffectiveAgentSettings(
        model =
            ModelRuntimeSettings(
                provider = Provider.OPENAI,
                baseUrl = Provider.OPENAI.defaultBaseUrl,
                modelName = Provider.OPENAI.defaultModel,
                temperature = null,
                reasoningEnabled = false,
            ),
        credentialRef = credentialRef,
        apiKey = apiKey,
        instructions = "test",
        skillPaths = emptyList(),
        skills = emptyList(),
    )

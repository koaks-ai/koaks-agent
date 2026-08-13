package org.koaks.agent.session

import org.koaks.agent.config.AgentConfig
import org.koaks.agent.config.MemorySettings
import org.koaks.agent.config.ModelDefaults
import org.koaks.agent.config.PromptSettings
import org.koaks.agent.config.SessionDefaults
import org.koaks.agent.config.SkillSettings
import org.koaks.agent.credential.ApiKey
import org.koaks.agent.definition.AgentDefinitionFactory
import org.koaks.agent.provider.Provider
import org.koaks.agent.provider.ProviderProfile
import org.koaks.agent.provider.ProviderProfiles
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.WindowMemoryProvider
import org.koaks.runtime.AgentRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class AgentChatSessionTest {
    @Test
    fun providerSwitchResetsToProviderDefaultModel() =
        withSession { session ->
            assertIs<SessionUpdateResult.Updated>(session.update(SessionCommand.SelectModel("gpt-alt")))

            val updated =
                assertIs<SessionUpdateResult.Updated>(
                    session.update(SessionCommand.SelectProvider(Provider.ANTHROPIC)),
                )

            assertEquals(Provider.ANTHROPIC, updated.snapshot.provider)
            assertEquals("claude-default", updated.snapshot.modelName)
            assertEquals(listOf("claude-default", "claude-alt"), updated.snapshot.availableModels)
        }

    @Test
    fun invalidModelReturnsRejectedWithoutChangingSnapshot() =
        withSession { session ->
            session.update(SessionCommand.SelectProvider(Provider.ANTHROPIC))

            val rejected = assertIs<SessionUpdateResult.Rejected>(session.update(SessionCommand.SelectModel("unknown")))

            assertEquals(
                SessionUpdateFailure.UnknownModel("unknown", listOf("claude-default", "claude-alt")),
                rejected.reason,
            )
            assertEquals("claude-default", session.snapshot.modelName)
        }

    @Test
    fun snapshotSummarizesCredentialsWithoutLeakingInlineApiKey() =
        withSession { session ->
            assertEquals(CredentialSummary.InlineConfigured, session.snapshot.credential)
            assertFalse(session.snapshot.toString().contains("super-secret"))

            session.update(SessionCommand.SelectProvider(Provider.ANTHROPIC))
            assertEquals(CredentialSummary.NotRequired, session.snapshot.credential)
        }

    @Test
    fun updatesKeepThreadAndMemoryPolicyStable() =
        withSession { session ->
            val threadId = session.snapshot.threadId
            val memoryProviderId = session.memoryProviderId

            session.update(SessionCommand.SelectProvider(Provider.ANTHROPIC))
            session.update(SessionCommand.SetReasoning(true))

            assertEquals(threadId, session.snapshot.threadId)
            assertEquals(memoryProviderId, session.memoryProviderId)
        }

    private fun withSession(block: (AgentChatSession) -> Unit) {
        AgentRuntime().use { runtime ->
            AgentChatSession(
                config = testConfig(),
                threadId = ThreadId("fixed-thread"),
                runtime = runtime,
                definitions = AgentDefinitionFactory(),
                memoryProvider = WindowMemoryProvider(16),
            ).use(block)
        }
    }
}

private fun testConfig(): AgentConfig =
    AgentConfig(
        model = ModelDefaults(Provider.OPENAI, "gpt-default", temperature = null, reasoningEnabled = false),
        providers =
            ProviderProfiles(
                profiles =
                    linkedMapOf(
                        Provider.OPENAI to
                            ProviderProfile(
                                provider = Provider.OPENAI,
                                baseUrl = Provider.OPENAI.defaultBaseUrl,
                                defaultModel = "gpt-default",
                                modelList = listOf("gpt-default", "gpt-alt"),
                                apiKey = ApiKey("super-secret"),
                            ),
                        Provider.ANTHROPIC to
                            ProviderProfile(
                                provider = Provider.ANTHROPIC,
                                baseUrl = Provider.ANTHROPIC.defaultBaseUrl,
                                defaultModel = "claude-default",
                                modelList = listOf("claude-default", "claude-alt"),
                            ),
                    ),
                configuredProviders = listOf(Provider.OPENAI, Provider.ANTHROPIC),
            ),
        prompt = PromptSettings("test"),
        memory = MemorySettings(16),
        skills = SkillSettings(emptyList(), emptyList()),
        session = SessionDefaults("config-thread"),
    )

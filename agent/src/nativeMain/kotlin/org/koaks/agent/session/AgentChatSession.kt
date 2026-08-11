package org.koaks.agent.session

import kotlinx.coroutines.flow.Flow
import org.koaks.agent.config.AgentConfig
import org.koaks.agent.definition.AgentDefinitionFactory
import org.koaks.agent.definition.EffectiveAgentSettings
import org.koaks.agent.provider.ModelRuntimeSettings
import org.koaks.agent.provider.Provider
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.memory.MemoryProvider
import org.koaks.framework.memory.MemoryProviderId
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.middleware.AgentListener
import org.koaks.runtime.AgentRuntime

public class AgentChatSession public constructor(
    private val config: AgentConfig,
    private val threadId: ThreadId,
    private val runtime: AgentRuntime,
    private val definitions: AgentDefinitionFactory,
    private val memoryProvider: MemoryProvider,
    private val listener: AgentListener? = null,
) : ChatSession,
    AutoCloseable {
    private var overrides = SessionOverrides()
    private var assistant: Agent? = null

    internal val memoryProviderId: MemoryProviderId
        get() = memoryProvider.id

    override val snapshot: SessionSnapshot
        get() {
            val active = activeSettings()
            return SessionSnapshot(
                provider = active.model.provider,
                modelName = active.model.modelName,
                baseUrl = active.model.baseUrl,
                credential =
                    when {
                        active.apiKey != null -> CredentialSummary.InlineConfigured
                        active.credentialRef != null ->
                            CredentialSummary.Reference(active.credentialRef.source, active.credentialRef.name)
                        else -> CredentialSummary.NotRequired
                    },
                threadId = threadId,
                historyMessages = config.memory.historyMessages,
                reasoningEnabled = active.model.reasoningEnabled,
                skillPaths = active.skillPaths,
                skills = active.skills,
                availableProviders = config.providers.availableProviders(),
                availableModels = config.providers.profileFor(active.model.provider).modelList,
            )
        }

    override fun update(command: SessionCommand): SessionUpdateResult =
        when (command) {
            is SessionCommand.SelectProvider -> {
                overrides = overrides.copy(provider = command.provider, modelName = null)
                resetAgent()
                SessionUpdateResult.Updated(snapshot)
            }
            is SessionCommand.SelectModel -> updateModel(command.modelName)
            is SessionCommand.SetReasoning -> {
                overrides = overrides.copy(reasoningEnabled = command.enabled)
                resetAgent()
                SessionUpdateResult.Updated(snapshot)
            }
        }

    override fun stream(input: String): Flow<AgentEvent> {
        val activeAgent =
            assistant ?: definitions.build(activeSettings(), memoryProvider, listener).also { replacement ->
                runtime.replaceAgent(replacement)
                assistant = replacement
            }
        return runtime.stream(activeAgent, input, thread = threadId)
    }

    private fun updateModel(value: String): SessionUpdateResult {
        val modelName = value.trim()
        if (modelName.isEmpty()) return SessionUpdateResult.Rejected(SessionUpdateFailure.BlankModel)
        val profile = config.providers.profileFor(activeProvider())
        if (profile.modelList.isNotEmpty() && modelName !in profile.modelList) {
            return SessionUpdateResult.Rejected(SessionUpdateFailure.UnknownModel(modelName, profile.modelList))
        }
        overrides = overrides.copy(modelName = modelName)
        resetAgent()
        return SessionUpdateResult.Updated(snapshot)
    }

    private fun activeProvider(): Provider = overrides.provider ?: config.model.provider

    private fun activeSettings(): EffectiveAgentSettings {
        val provider = activeProvider()
        val profile = config.providers.profileFor(provider)
        val modelName =
            overrides.modelName
                ?: if (provider == config.model.provider) config.model.modelName else profile.defaultModel
        return EffectiveAgentSettings(
            model =
                ModelRuntimeSettings(
                    provider = provider,
                    baseUrl = profile.baseUrl,
                    modelName = modelName,
                    temperature = config.model.temperature,
                    reasoningEnabled = overrides.reasoningEnabled ?: config.model.reasoningEnabled,
                ),
            credentialRef = profile.credentialRef,
            apiKey = profile.apiKey,
            instructions = config.prompt.instructions,
            skillPaths = config.skills.paths,
            skills = config.skills.enabled,
        )
    }

    private fun resetAgent() {
        assistant?.close()
        assistant = null
    }

    override fun close() {
        resetAgent()
    }

    private data class SessionOverrides(
        val provider: Provider? = null,
        val modelName: String? = null,
        val reasoningEnabled: Boolean? = null,
    )
}

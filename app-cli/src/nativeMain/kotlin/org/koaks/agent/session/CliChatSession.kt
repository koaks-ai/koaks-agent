package org.koaks.agent.session

import kotlinx.coroutines.flow.Flow
import org.koaks.agent.app.ChatSessionPort
import org.koaks.agent.config.AgentConfig
import org.koaks.agent.config.SessionPreferences
import org.koaks.agent.config.withPreferences
import org.koaks.agent.definition.AgentDefinitionFactory
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.memory.ThreadId
import org.koaks.runtime.AgentRuntime

internal class CliChatSession(
    private val resolvedConfig: AgentConfig,
    private val runtime: AgentRuntime,
    private val definitions: AgentDefinitionFactory,
    private val trace: CliTrace? = null,
) : ChatSessionPort,
    AutoCloseable {
    private var preferences = SessionPreferences()

    override val config: AgentConfig
        get() = resolvedConfig.withPreferences(preferences)

    private var assistant: Agent? = null

    override fun updatePreferences(transform: (SessionPreferences) -> SessionPreferences) {
        preferences = transform(preferences)
        resetAgent()
    }

    override fun stream(input: String): Flow<AgentEvent> {
        val activeAgent =
            assistant ?: definitions.build(config, trace).also { replacement ->
                runtime.replaceAgent(replacement)
                assistant = replacement
            }
        return runtime.stream(activeAgent, input, thread = ThreadId(config.threadId))
    }

    private fun resetAgent() {
        assistant?.close()
        assistant = null
    }

    override fun close() {
        resetAgent()
    }
}

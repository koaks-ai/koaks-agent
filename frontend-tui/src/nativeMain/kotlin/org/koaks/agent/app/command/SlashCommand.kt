package org.koaks.agent.app.command

import org.koaks.agent.config.AgentConfig
import org.koaks.agent.config.SessionPreferences

internal enum class MessageTone { NORMAL, ERROR }

internal data class CommandMessage(
    val text: String,
    val tone: MessageTone = MessageTone.NORMAL,
)

internal sealed interface CommandResult {
    data class Continue(
        val message: CommandMessage? = null,
    ) : CommandResult

    data class Update(
        val transform: (SessionPreferences) -> SessionPreferences,
        val message: CommandMessage,
    ) : CommandResult

    data object Exit : CommandResult
}

internal interface SlashCommand {
    val names: Set<String>
    val description: String

    fun run(
        input: String,
        config: AgentConfig,
        registry: CommandRegistry,
    ): CommandResult
}

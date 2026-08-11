package org.koaks.agent.tui.command

import org.koaks.agent.session.SessionCommand
import org.koaks.agent.session.SessionSnapshot

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
        val command: SessionCommand,
        val message: (SessionSnapshot) -> CommandMessage,
    ) : CommandResult

    data object Exit : CommandResult
}

internal interface SlashCommand {
    val names: Set<String>
    val description: String

    fun run(
        input: String,
        snapshot: SessionSnapshot,
        registry: CommandRegistry,
    ): CommandResult
}

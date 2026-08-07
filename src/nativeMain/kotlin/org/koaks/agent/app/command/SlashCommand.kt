package org.koaks.agent.app.command

import org.koaks.agent.app.AgentContext

internal sealed interface CommandResult {
    object Continue : CommandResult
    object Exit : CommandResult
}

internal interface SlashCommand {
    val names: Set<String>
    val description: String

    fun run(input: String, context: AgentContext, registry: CommandRegistry): CommandResult
}

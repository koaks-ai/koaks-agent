package org.koaks.agent.app.command

import org.koaks.agent.config.AgentConfig

internal class CommandRegistry(
    commands: List<SlashCommand>,
) {
    val commands: List<SlashCommand> = commands.toList()
    private val byName =
        commands
            .flatMap { command -> command.names.map { it.normalizeName() to command } }
            .toMap()

    val suggestions: List<CommandSuggestion> =
        commands.flatMap { command ->
            command.names.filter { it.startsWith("/") }.map { CommandSuggestion(it, command.description) }
        }
    val commandNames: Set<String> = byName.keys

    fun dispatch(
        input: String,
        config: AgentConfig,
    ): CommandResult? {
        val name = input.trim().substringBefore(" ").normalizeName()
        byName[name]?.let { return it.run(input, config, this) }
        return if (name.startsWith("/") || name.startsWith(":")) {
            CommandResult.Continue(CommandMessage("[unknown command] $name. Type /help for commands.", MessageTone.ERROR))
        } else {
            null
        }
    }

    fun isBuiltinCommand(name: String): Boolean = name.normalizeName() in byName

    companion object {
        fun builtins(): CommandRegistry = CommandRegistry(builtinCommands())
    }
}

internal data class CommandSuggestion(
    val name: String,
    val description: String,
)

private fun String.normalizeName(): String = if (startsWith("/")) lowercase() else this

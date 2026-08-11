package org.koaks.agent.tui.command

import org.koaks.agent.provider.Provider
import org.koaks.agent.session.CredentialSummary
import org.koaks.agent.session.SessionCommand
import org.koaks.agent.session.SessionSnapshot

internal fun builtinCommands(): List<SlashCommand> =
    listOf(
        HelpCommand,
        StatusCommand,
        ProviderCommand,
        ModelCommand,
        ReasoningCommand,
        SkillsCommand,
        ExitCommand,
    )

private object HelpCommand : SlashCommand {
    override val names = setOf("/help")
    override val description = "Show this help"

    override fun run(
        input: String,
        snapshot: SessionSnapshot,
        registry: CommandRegistry,
    ): CommandResult {
        val width = registry.commands.maxOf { it.names.joinToString(", ").length }
        return CommandResult.Continue(
            CommandMessage(
                buildString {
                    appendLine("Commands:")
                    registry.commands.forEach { command ->
                        val names = command.names.joinToString(", ")
                        appendLine("  ${names.padEnd(width)}  ${command.description}")
                    }
                }.trimEnd(),
            ),
        )
    }
}

private object StatusCommand : SlashCommand {
    override val names = setOf("/status")
    override val description = "Show current session config"

    override fun run(
        input: String,
        snapshot: SessionSnapshot,
        registry: CommandRegistry,
    ): CommandResult =
        CommandResult.Continue(
            CommandMessage(
                """
                Provider: ${snapshot.provider.id}
                Model: ${snapshot.modelName}
                Base URL: ${snapshot.baseUrl}
                Credential: ${snapshot.credential.display()}
                Thread: ${snapshot.threadId.value}
                History: ${snapshot.historyMessages} messages
                Reasoning: ${if (snapshot.reasoningEnabled) "on" else "off"}
                """.trimIndent(),
            ),
        )
}

private object ProviderCommand : SlashCommand {
    override val names = setOf("/provider")
    override val description = "Show or set provider"

    override fun run(
        input: String,
        snapshot: SessionSnapshot,
        registry: CommandRegistry,
    ): CommandResult {
        val value = args(input)
        if (value.isBlank()) {
            return CommandResult.Continue(
                CommandMessage(
                    "Provider: ${snapshot.provider.id}\nAvailable: ${snapshot.availableProviders.joinToString(", ") { it.id }}",
                ),
            )
        }
        val provider =
            Provider.fromId(value)
                ?: return error("Unknown provider '$value'. Expected ${Provider.idsForMessage()}.")
        if (provider !in snapshot.availableProviders) {
            return error("Provider '${provider.id}' is not configured.")
        }
        return CommandResult.Update(SessionCommand.SelectProvider(provider)) { updated ->
            CommandMessage("Provider set to ${provider.id}; model set to ${updated.modelName}.")
        }
    }
}

private object ModelCommand : SlashCommand {
    override val names = setOf("/model")
    override val description = "Show or set model"

    override fun run(
        input: String,
        snapshot: SessionSnapshot,
        registry: CommandRegistry,
    ): CommandResult {
        val value = args(input)
        if (value.isBlank()) {
            return CommandResult.Continue(
                CommandMessage(
                    if (snapshot.availableModels.isEmpty()) {
                        "Model: ${snapshot.modelName}"
                    } else {
                        "Model: ${snapshot.modelName}\nAvailable: ${snapshot.availableModels.joinToString(", ")}"
                    },
                ),
            )
        }
        return CommandResult.Update(SessionCommand.SelectModel(value)) { updated ->
            CommandMessage("Model set to ${updated.modelName}.")
        }
    }
}

private object ReasoningCommand : SlashCommand {
    override val names = setOf("/reasoning")
    override val description = "Show or toggle reasoning"

    override fun run(
        input: String,
        snapshot: SessionSnapshot,
        registry: CommandRegistry,
    ): CommandResult =
        when (args(input).lowercase()) {
            "" -> CommandResult.Continue(CommandMessage("Reasoning: ${if (snapshot.reasoningEnabled) "on" else "off"}"))
            "on", "true", "1", "yes" ->
                CommandResult.Update(SessionCommand.SetReasoning(true)) { CommandMessage("Reasoning enabled.") }
            "off", "false", "0", "no" ->
                CommandResult.Update(SessionCommand.SetReasoning(false)) { CommandMessage("Reasoning disabled.") }
            else -> error("Usage: /reasoning <on|off>")
        }
}

private object SkillsCommand : SlashCommand {
    override val names = setOf("/skills")
    override val description = "Show fixed Skill configuration"

    override fun run(
        input: String,
        snapshot: SessionSnapshot,
        registry: CommandRegistry,
    ): CommandResult =
        CommandResult.Continue(
            CommandMessage(
                if (snapshot.skillPaths.isEmpty()) {
                    "Skills: disabled\nConfigure skill_paths in ~/.koaks/config.toml."
                } else {
                    "Skill sources:\n${snapshot.skillPaths.joinToString("\n") { "  $it" }}\nEnabled: " +
                        if (snapshot.skills.isEmpty()) "all discovered Skills" else snapshot.skills.joinToString(", ")
                },
            ),
        )
}

private object ExitCommand : SlashCommand {
    override val names = setOf("/exit")
    override val description = "Quit the agent"

    override fun run(
        input: String,
        snapshot: SessionSnapshot,
        registry: CommandRegistry,
    ): CommandResult = CommandResult.Exit
}

private fun CredentialSummary.display(): String =
    when (this) {
        is CredentialSummary.Reference -> "${source.name.lowercase()}:$name"
        CredentialSummary.InlineConfigured -> "api_key:(configured)"
        CredentialSummary.NotRequired -> "(not required)"
    }

private fun error(message: String): CommandResult = CommandResult.Continue(CommandMessage("[error] $message", MessageTone.ERROR))

private fun args(input: String): String = input.substringAfter(" ", "").trim()

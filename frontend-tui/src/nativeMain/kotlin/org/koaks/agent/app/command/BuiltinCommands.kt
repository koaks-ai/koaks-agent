package org.koaks.agent.app.command

import org.koaks.agent.config.AgentConfig
import org.koaks.agent.config.CliException
import org.koaks.agent.config.Provider
import org.koaks.agent.config.availableProviders
import org.koaks.agent.config.nonBlank
import org.koaks.agent.config.profileFor

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
        config: AgentConfig,
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
        config: AgentConfig,
        registry: CommandRegistry,
    ): CommandResult =
        CommandResult.Continue(
            CommandMessage(
                """
                Provider: ${config.provider.id}
                Model: ${config.modelName}
                Base URL: ${config.baseUrl}
                Credential: ${config.credentialRef?.let {
                    "${it.source.name.lowercase()}:${it.name}"
                } ?: if (config.apiKey != null) "api_key:(configured)" else "(not required)"}
                Thread: ${config.threadId}
                History: ${config.historyMessages} messages
                Reasoning: ${if (config.showReasoning) "on" else "off"}
                """.trimIndent(),
            ),
        )
}

private object ProviderCommand : SlashCommand {
    override val names = setOf("/provider")
    override val description = "Show or set provider"

    override fun run(
        input: String,
        config: AgentConfig,
        registry: CommandRegistry,
    ): CommandResult {
        val value = args(input)
        if (value.isBlank()) {
            return CommandResult.Continue(
                CommandMessage(
                    "Provider: ${config.provider.id}\nAvailable: ${config.availableProviders().joinToString(", ") { it.id }}",
                ),
            )
        }
        return try {
            val provider = Provider.parse(value)
            val profile = config.profileFor(provider)
            CommandResult.Update(
                transform = {
                    it.copy(
                        provider = provider,
                        modelName = null,
                    )
                },
                message = CommandMessage("Provider set to ${provider.id}; model set to ${profile.defaultModel}."),
            )
        } catch (error: CliException) {
            CommandResult.Continue(CommandMessage("[error] ${error.message}", MessageTone.ERROR))
        }
    }
}

private object ModelCommand : SlashCommand {
    override val names = setOf("/model")
    override val description = "Show or set model"

    override fun run(
        input: String,
        config: AgentConfig,
        registry: CommandRegistry,
    ): CommandResult {
        val value = args(input)
        val profile = config.profileFor(config.provider)
        if (value.isBlank()) {
            val available = profile.modelList
            return CommandResult.Continue(
                CommandMessage(
                    if (available.isEmpty()) {
                        "Model: ${config.modelName}"
                    } else {
                        "Model: ${config.modelName}\nAvailable: ${available.joinToString(", ")}"
                    },
                ),
            )
        }
        return try {
            val model = value.nonBlank("/model")
            if (profile.modelList.isNotEmpty() && model !in profile.modelList) {
                throw CliException("Unknown model '$model'. Expected ${profile.modelList.joinToString(", ")}.")
            }
            CommandResult.Update({ it.copy(modelName = model) }, CommandMessage("Model set to $model."))
        } catch (error: CliException) {
            CommandResult.Continue(CommandMessage("[error] ${error.message}", MessageTone.ERROR))
        }
    }
}

private object ReasoningCommand : SlashCommand {
    override val names = setOf("/reasoning")
    override val description = "Show or toggle reasoning"

    override fun run(
        input: String,
        config: AgentConfig,
        registry: CommandRegistry,
    ): CommandResult =
        when (args(input).lowercase()) {
            "" -> CommandResult.Continue(CommandMessage("Reasoning: ${if (config.showReasoning) "on" else "off"}"))
            "on", "true", "1", "yes" ->
                CommandResult.Update(
                    { it.copy(showReasoning = true) },
                    CommandMessage("Reasoning enabled."),
                )
            "off", "false", "0", "no" ->
                CommandResult.Update(
                    { it.copy(showReasoning = false) },
                    CommandMessage("Reasoning disabled."),
                )
            else -> CommandResult.Continue(CommandMessage("[error] Usage: /reasoning <on|off>", MessageTone.ERROR))
        }
}

private object SkillsCommand : SlashCommand {
    override val names = setOf("/skills")
    override val description = "Show fixed Skill configuration"

    override fun run(
        input: String,
        config: AgentConfig,
        registry: CommandRegistry,
    ): CommandResult =
        CommandResult.Continue(
            CommandMessage(
                if (config.skillPaths.isEmpty()) {
                    "Skills: disabled\nConfigure skill_paths in ~/.koaks/config.toml."
                } else {
                    "Skill sources:\n${config.skillPaths.joinToString("\n") { "  $it" }}\nEnabled: " +
                        if (config.skills.isEmpty()) "all discovered Skills" else config.skills.joinToString(", ")
                },
            ),
        )
}

private object ExitCommand : SlashCommand {
    override val names = setOf("/exit")
    override val description = "Quit the agent"

    override fun run(
        input: String,
        config: AgentConfig,
        registry: CommandRegistry,
    ): CommandResult = CommandResult.Exit
}

private fun args(input: String): String = input.substringAfter(" ", "").trim()

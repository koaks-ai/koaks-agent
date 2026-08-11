package org.koaks.agent.definition

import org.koaks.agent.config.AgentConfig
import org.koaks.agent.config.CliException
import org.koaks.agent.config.Provider
import org.koaks.agent.credential.CredentialResolver
import org.koaks.agent.credential.ToolApproval
import org.koaks.agent.provider.ProviderRegistry
import org.koaks.agent.tool.registerBuiltinCliTools
import org.koaks.agent.tool.registerSubagentBuiltinCliTools
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.agent
import org.koaks.framework.middleware.AgentListener
import org.koaks.framework.middleware.HumanApproval

public class AgentDefinitionFactory public constructor(
    private val credentials: CredentialResolver,
    private val providers: ProviderRegistry = ProviderRegistry.defaults(),
    private val toolApproval: ToolApproval = ToolApproval { _, _ -> false },
) {
    public fun build(
        config: AgentConfig,
        listener: AgentListener? = null,
    ): Agent {
        val credential = credential(config)
        val subagent = buildSubagent(config, credential)
        return agent {
            id = MAIN_AGENT_ID
            name = MAIN_AGENT_ID
            instructions = config.instructions
            memory { window(config.historyMessages) }
            terminateAfter(maxSteps = MAX_STEPS)
            listener?.let { install(it) }
            install(
                HumanApproval(
                    guard = { context -> context.call.name in SIDE_EFFECT_TOOLS },
                    approve = { context -> toolApproval.approve(context.call.name, context.call.arguments) },
                ),
            )
            tools { registerBuiltinCliTools(SubagentDefinitionProvider { subagent }) }
            if (config.skillPaths.isNotEmpty()) {
                skills {
                    config.skillPaths.forEach { source(it) }
                    config.skills.forEach { use(it) }
                }
            }
            model { providers.select(this, config, credential) }
        }
    }

    private fun buildSubagent(
        config: AgentConfig,
        credential: String,
    ): Agent =
        agent {
            id = SUBAGENT_ID
            name = SUBAGENT_ID
            terminateAfter(maxSteps = MAX_STEPS)
            tools { registerSubagentBuiltinCliTools() }
            model { providers.select(this, config, credential) }
        }

    private fun credential(config: AgentConfig): String {
        if (config.provider == Provider.OLLAMA) return "ollama"
        config.apiKey?.let { return it.value }
        val reference =
            config.credentialRef
                ?: throw CliException("Missing credential reference for ${config.provider.id}.")
        return credentials.resolve(reference)
            ?: throw CliException(
                "Credential '${reference.name}' (${reference.source.name.lowercase()}) is unavailable for ${config.provider.id}.",
            )
    }

    private companion object {
        const val MAIN_AGENT_ID = "koaks-agent"
        const val SUBAGENT_ID = "koaks-agent-subagent"
        const val MAX_STEPS = 1024
        val SIDE_EFFECT_TOOLS = setOf("Bash", "PowerShell", "Write", "Edit")
    }
}

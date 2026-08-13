package org.koaks.agent.definition

import org.koaks.agent.credential.ApiKey
import org.koaks.agent.provider.ModelRuntimeSettings
import org.koaks.agent.provider.Provider
import org.koaks.agent.provider.ProviderRegistry
import org.koaks.agent.tool.BuiltinToolSet
import org.koaks.agent.tool.approval.ToolApprovalPort
import org.koaks.agent.tool.delegate.SubagentFactory
import org.koaks.agent.tool.policy.ProcessPolicy
import org.koaks.agent.tool.policy.WorkspaceAccessPolicy
import org.koaks.agent.tool.registerTools
import org.koaks.agent.tool.sideEffectToolNames
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentBuilder
import org.koaks.framework.loop.agent
import org.koaks.framework.memory.MemoryProvider
import org.koaks.framework.middleware.AgentListener
import org.koaks.framework.middleware.HumanApproval

public class AgentDefinitionFactory public constructor(
    private val providers: ProviderRegistry = ProviderRegistry.defaults(),
    private val toolApproval: ToolApprovalPort = ToolApprovalPort { _, _ -> false },
    workspacePolicy: WorkspaceAccessPolicy = WorkspaceAccessPolicy(),
    processPolicy: ProcessPolicy = ProcessPolicy(),
) {
    private val builtinTools = BuiltinToolSet(workspacePolicy, processPolicy)

    internal fun build(
        settings: EffectiveAgentSettings,
        memoryProvider: MemoryProvider,
        listener: AgentListener? = null,
    ): Agent {
        val credential = credential(settings)
        val subagentTools = builtinTools.subagentTools()
        val subagent = buildSubagent(settings, credential, subagentTools)
        val mainTools = builtinTools.mainTools(SubagentFactory { subagent })
        return agent {
            id = MAIN_AGENT_ID
            name = MAIN_AGENT_ID
            instructions = settings.instructions
            memory { custom(memoryProvider.id, memoryProvider) }
            terminateAfter(maxSteps = MAX_STEPS)
            listener?.let { install(it) }
            installApproval(mainTools.sideEffectToolNames())
            tools { registerTools(mainTools) }
            if (settings.skillPaths.isNotEmpty()) {
                skills {
                    settings.skillPaths.forEach { source(it) }
                    settings.skills.forEach { use(it) }
                }
            }
            model { providers.select(this, settings.model, credential) }
        }
    }

    private fun buildSubagent(
        settings: EffectiveAgentSettings,
        credential: String,
        tools: List<org.koaks.framework.tool.Tool<*>>,
    ): Agent = agent {
        id = SUBAGENT_ID
        name = SUBAGENT_ID
        terminateAfter(maxSteps = MAX_STEPS)
        installApproval(tools.sideEffectToolNames())
        tools {
            registerTools(tools)
        }
        model {
            providers.select(this, settings.model, credential)
        }
    }

    internal fun buildSubagent(settings: EffectiveAgentSettings): Agent {
        val tools = builtinTools.subagentTools()
        return buildSubagent(settings, credential(settings), tools)
    }

    private fun credential(settings: EffectiveAgentSettings): String {
        if (settings.model.provider == Provider.OLLAMA) return "ollama"
        settings.apiKey?.let { return it.value }
        throw AgentSetupException(SetupFailure.MissingApiKey(settings.model.provider))
    }

    private fun AgentBuilder.installApproval(toolNames: Set<String>) {
        if (toolNames.isEmpty()) return
        install(
            HumanApproval(
                guard = { context -> context.call.name in toolNames },
                approve = { context -> toolApproval.approve(context.call.name, context.call.arguments) },
            ),
        )
    }

    private companion object {
        const val MAIN_AGENT_ID = "koaks-agent"
        const val SUBAGENT_ID = "koaks-agent-subagent"
        const val MAX_STEPS = 1024
    }
}

internal data class EffectiveAgentSettings(
    val model: ModelRuntimeSettings,
    val apiKey: ApiKey?,
    val instructions: String,
    val skillPaths: List<String>,
    val skills: List<String>,
)

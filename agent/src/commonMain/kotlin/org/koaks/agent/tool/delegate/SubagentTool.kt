package org.koaks.agent.tool.delegate

import kotlinx.serialization.Serializable
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentResult
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.AgentFrameworkException
import org.koaks.framework.tool.Tool
import org.koaks.runtime.resource.ChildConversation
import org.koaks.runtime.resource.ChildFailurePolicy
import org.koaks.runtime.resource.spawnChild

@Serializable
internal data class SubagentInput(
    /** Short label shown in the CLI tool line. */
    val description: String,
    /** Complete prompt written by the parent model and handed to the sub-agent unchanged. */
    val prompt: String,
)

/**
 * Spawns an isolated child agent, awaits its result, and returns the text to the parent.
 *
 * Multiple Subagent calls in one model step run in parallel (AgentRunner executes tools
 * concurrently). Children use ephemeral conversations, so parallel execution does not
 * create persistent Thread bindings.
 */
internal fun interface SubagentFactory {
    fun definition(): Agent
}

internal class SubagentTool(
    private val definitions: SubagentFactory,
) : Tool<SubagentInput> {
    internal constructor(subagent: Agent) : this(SubagentFactory { subagent })

    override val name: String = "Subagent"
    override val description: String =
        "Create an isolated sub-agent, give it the supplied prompt, wait for completion, " +
            "and return its final answer. `description` is a short display label. `prompt` must be " +
            "the complete instructions written by the parent model, including all necessary context, " +
            "constraints, and desired output. Call Subagent multiple times in the same step to run " +
            "independent sub-agents concurrently. Sub-agents cannot create further sub-agents."
    override val inputSerializer = SubagentInput.serializer()

    override suspend fun execute(input: SubagentInput): String {
        val description = input.description.trim()
        val prompt = input.prompt.trim()
        if (description.isEmpty()) failTask("description is required")
        if (prompt.isEmpty()) failTask("prompt is required")

        val result =
            spawnChild(
                agent = definitions.definition(),
                input = "$prompt\n\n$READ_ONLY_FILE_GUARD",
                failurePolicy = ChildFailurePolicy.CAPTURE,
                conversation = ChildConversation.Ephemeral,
            ).await()

        return formatResult(description, result)
    }

    private fun formatResult(
        description: String,
        result: AgentResult,
    ): String {
        val header = "[subagent] $description"
        return when (result) {
            is AgentResult.Completed -> {
                val body = result.text.trim()
                if (body.isEmpty()) {
                    "$header\n(completed with empty output)"
                } else {
                    truncate("$header\n$body")
                }
            }
            is AgentResult.Terminated -> {
                val body = result.text.trim()
                val details =
                    truncate(
                        buildString {
                            appendLine(header)
                            appendLine("Subagent terminated: ${result.reason}")
                            if (body.isNotEmpty()) append(body)
                        }.trimEnd(),
                    )
                failTask(details)
            }
            is AgentResult.Failed -> throw AgentFrameworkException(result.error)
        }
    }

    private fun failTask(
        message: String,
        cause: Throwable? = null,
    ): Nothing =
        throw AgentFrameworkException(
            AgentError.ToolError(
                toolName = name,
                message = message,
                retriable = false,
                cause = cause,
            ),
        )

    private fun truncate(text: String): String {
        if (text.length <= MAX_TASK_RESULT_CHARS) return text
        return text.take(MAX_TASK_RESULT_CHARS) +
            "\n[truncated to $MAX_TASK_RESULT_CHARS of ${text.length} characters]"
    }

    private companion object {
        const val MAX_TASK_RESULT_CHARS = 80_000
        const val READ_ONLY_FILE_GUARD =
            "Important: Unless the task above explicitly asks you to modify or edit files, " +
                "you must never modify or edit any files and may only inspect or read them."
    }
}

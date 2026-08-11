package org.koaks.agent.tool.shell

import kotlinx.serialization.Serializable
import org.koaks.agent.platform.BashCommandLine
import org.koaks.agent.platform.NativeProcess
import org.koaks.agent.platform.currentOperatingSystemName
import org.koaks.agent.tool.executeToolSafely
import org.koaks.agent.tool.policy.ProcessPolicy
import org.koaks.framework.tool.Tool
import org.koaks.runtime.resource.AccessMode
import org.koaks.runtime.resource.withRuntimeResource

@Serializable
internal data class ShellInput(
    val command: String,
)

internal class ShellTool(
    private val policy: ProcessPolicy = ProcessPolicy(),
) : Tool<ShellInput> {
    override val name: String = BashCommandLine.toolName
    override val description: String =
        "Run a shell command from the current working directory. " +
            "Current operating system: $currentOperatingSystemName. " +
            "Current shell: ${BashCommandLine.shellName}. " +
            "${BashCommandLine.commandSyntaxGuidance} " +
            "Use this for inspecting the project, running tests, and other shell tasks. " +
            "The command's stdout and stderr are returned, with long output truncated."
    override val inputSerializer = ShellInput.serializer()
    override val hasSideEffects: Boolean = true

    override suspend fun execute(input: ShellInput): String =
        executeToolSafely(name) {
            val command = input.command.trim()
            if (command.isEmpty()) return@executeToolSafely "Error: command is required."
            if (command.length > policy.maxCommandChars) {
                return@executeToolSafely "Error: command exceeds the ${policy.maxCommandChars} character limit."
            }

            val result =
                withRuntimeResource("local-process", AccessMode.WRITE) {
                    NativeProcess.runShell(command, policy.maxOutputChars, policy.timeoutMillis)
                }
            val statusMark = if (result.status == 0) "✓" else "✗"
            return@executeToolSafely buildString {
                appendLine("$statusMark exit ${result.status}")
                val body = result.output.trimEnd()
                if (body.isNotEmpty()) {
                    append(body)
                    appendLine()
                }
                if (result.truncated) {
                    appendLine(
                        "[truncated to ${policy.maxOutputChars} of ${result.totalOutputChars} characters]",
                    )
                }
            }.trimEnd()
        }
}

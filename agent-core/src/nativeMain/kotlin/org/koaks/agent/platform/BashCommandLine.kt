package org.koaks.agent.platform

internal expect object BashCommandLine {
    public val toolName: String
    public val shellName: String
    public val commandSyntaxGuidance: String

    public fun execute(
        command: String,
        maxOutputChars: Int,
        timeoutMillis: Long,
    ): CommandResult
}

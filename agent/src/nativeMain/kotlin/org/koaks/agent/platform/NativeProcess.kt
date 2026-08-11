package org.koaks.agent.platform

internal object NativeProcess {
    fun runShell(
        command: String,
        maxOutputChars: Int,
        timeoutMillis: Long = 120_000,
    ): CommandResult = BashCommandLine.execute(command, maxOutputChars, timeoutMillis)
}

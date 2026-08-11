package org.koaks.agent.config

public data class CliOptions public constructor(
    public val command: CliCommand = CliCommand.CHAT,
    public val showHelp: Boolean = false,
    public val force: Boolean = false,
)

public enum class CliCommand private constructor() {
    CHAT,
    INIT,
}

package org.koaks.agent.tui.platform

internal data class TerminalSize(
    val rows: Int,
    val columns: Int,
)

internal expect object Terminal {
    fun size(): TerminalSize?

    fun stdinIsTty(): Boolean

    fun stdoutIsTty(): Boolean
}

internal expect fun platformTerminalSize(): TerminalSize?

internal expect fun platformStdinIsTty(): Boolean

internal expect fun platformStdoutIsTty(): Boolean

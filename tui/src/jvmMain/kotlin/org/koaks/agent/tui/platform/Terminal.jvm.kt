package org.koaks.agent.tui.platform

internal actual object Terminal {
    actual fun size(): TerminalSize? = platformTerminalSize()

    actual fun stdinIsTty(): Boolean = platformStdinIsTty()

    actual fun stdoutIsTty(): Boolean = platformStdoutIsTty()
}

internal actual fun platformTerminalSize(): TerminalSize? =
    jvmTerminal?.let { terminal ->
        val rows = terminal.height
        val columns = terminal.width
        if (rows > 0 && columns > 0) TerminalSize(rows, columns) else null
    }

internal actual fun platformStdinIsTty(): Boolean = jvmTerminal != null

internal actual fun platformStdoutIsTty(): Boolean = jvmTerminal != null

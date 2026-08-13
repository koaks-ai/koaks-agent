package org.koaks.agent.tui.platform

internal actual object Terminal {
    actual fun size(): TerminalSize? = platformTerminalSize()

    actual fun stdinIsTty(): Boolean = platformStdinIsTty()

    actual fun stdoutIsTty(): Boolean = platformStdoutIsTty()
}

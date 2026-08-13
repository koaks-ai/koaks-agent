package org.koaks.agent.tui.platform

import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder

internal val jvmTerminal: Terminal? by lazy {
    runCatching {
        TerminalBuilder
            .builder()
            .system(true)
            .dumb(false)
            .jna(true)
            .build()
    }.getOrNull()
}

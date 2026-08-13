package org.koaks.agent.tui.io

import org.koaks.agent.tui.platform.jvmTerminal

internal actual object PlatformConsole {
    actual fun write(text: String) {
        val terminal = jvmTerminal
        if (terminal == null) {
            kotlin.io.print(text)
        } else {
            terminal.writer().print(text)
        }
    }

    actual fun writeLine(text: String) {
        val terminal = jvmTerminal
        if (terminal == null) {
            kotlin.io.println(text)
        } else {
            terminal.writer().println(text)
        }
    }

    actual fun flush() {
        jvmTerminal?.flush() ?: System.out.flush()
    }
}

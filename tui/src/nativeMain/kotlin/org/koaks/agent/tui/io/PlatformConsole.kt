@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.agent.tui.io

import platform.posix.fflush
import platform.posix.stdout

internal actual object PlatformConsole {
    actual fun write(text: String) = print(text)

    actual fun writeLine(text: String) = println(text)

    actual fun flush() {
        fflush(stdout)
    }
}

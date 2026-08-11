@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.agent.cli.platform

import platform.posix.fflush
import platform.posix.fputs
import platform.posix.stdout

internal object NativeConsole {
    fun writeLine(text: String) {
        fputs(text, stdout)
        fputs("\n", stdout)
        fflush(stdout)
    }
}

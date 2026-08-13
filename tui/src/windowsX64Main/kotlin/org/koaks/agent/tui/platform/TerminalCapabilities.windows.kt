@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.agent.tui.platform

import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.GetConsoleMode
import platform.windows.GetStdHandle
import platform.windows.STD_INPUT_HANDLE
import platform.windows.STD_OUTPUT_HANDLE

internal actual fun platformStdinIsTty(): Boolean = isConsoleHandle(STD_INPUT_HANDLE)

internal actual fun platformStdoutIsTty(): Boolean = isConsoleHandle(STD_OUTPUT_HANDLE)

private fun isConsoleHandle(stdHandle: UInt): Boolean =
    memScoped {
        val mode = alloc<UIntVar>()
        GetConsoleMode(GetStdHandle(stdHandle), mode.ptr) != 0
    }

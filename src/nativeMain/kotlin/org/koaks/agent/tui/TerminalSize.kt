package org.koaks.agent.tui

internal data class NativeTerminalSize(
    val rows: Int,
    val columns: Int,
)

internal expect fun nativeTerminalSize(): NativeTerminalSize?

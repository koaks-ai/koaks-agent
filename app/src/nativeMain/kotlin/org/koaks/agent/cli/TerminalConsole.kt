package org.koaks.agent.cli

import org.koaks.agent.cli.platform.NativeConsole

internal fun formatCrashReport(error: Throwable): String {
    val type = error::class.simpleName ?: error::class.qualifiedName ?: "Throwable"
    val message = error.message?.takeIf { it.isNotBlank() }
    val header = if (message != null) "[fatal] $type: $message" else "[fatal] $type"
    return buildString {
        append(header)
        append('\n')
        append(error.stackTraceToString().trimEnd())
    }
}

internal object TerminalConsole {
    fun writeLine(text: String): Unit = NativeConsole.writeLine(text)

    fun printFatal(error: Throwable): Unit = NativeConsole.writeLine(formatCrashReport(error))
}

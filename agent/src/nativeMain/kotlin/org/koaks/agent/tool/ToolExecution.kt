package org.koaks.agent.tool

import kotlinx.coroutines.CancellationException
import org.koaks.agent.platform.NumberedTextLine
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.AgentFrameworkException

internal suspend fun executeToolSafely(
    toolName: String,
    block: suspend () -> String,
): String =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: AgentFrameworkException) {
        throw failure
    } catch (failure: Throwable) {
        val type = failure::class.simpleName ?: failure::class.qualifiedName ?: "Throwable"
        val message = failure.message?.takeIf { it.isNotBlank() }
        val detail = if (message == null) "$type" else "$type: $message"
        val report =
            buildString {
                append("tool '$toolName' crashed: ")
                append(detail)
                val stack = failure.stackTraceToString().trimEnd()
                if (stack.isNotEmpty()) {
                    append('\n')
                    append(stack)
                }
            }
        throw AgentFrameworkException(
            AgentError.ToolError(
                toolName = toolName,
                message = report,
                retriable = false,
                cause = failure,
            ),
        )
    }

internal fun displayName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

internal fun formatNumberedLines(
    lines: List<NumberedTextLine>,
    totalLines: Long,
): String {
    val width =
        maxOf(
            totalLines.toString().length,
            lines
                .lastOrNull()
                ?.number
                ?.toString()
                ?.length ?: 1,
        )
    return lines.joinToString("\n") { line ->
        "${line.number.toString().padStart(width)} | ${line.text}"
    }
}

internal const val DEFAULT_READ_WINDOW_LINES = 500
internal const val MAX_AUTO_READ_LINES = 400
internal const val MAX_READ_WINDOW_LINES = 500
internal const val MAX_AUTO_READ_CHARS = 100_000
internal const val MAX_READ_WINDOW_CHARS = 100_000
internal const val MAX_EDIT_FILE_BYTES = 1_000_000L
internal const val MAX_WRITE_FILE_CHARS = 1_000_000
internal const val EDIT_PREVIEW_MAX_LINES = 20
internal const val EDIT_PREVIEW_MAX_LINE_CHARS = 200

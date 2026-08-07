package org.koaks.agent.tool

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.koaks.agent.config.AgentConfig
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.AgentFrameworkException
import org.koaks.framework.loop.ToolScope
import org.koaks.framework.tool.Tool

internal fun ToolScope.registerBuiltinCliTools(
    config: AgentConfig,
) {
    tool(BashTool)
    tool(ReadTool)
    tool(WriteTool)
    tool(EditTool)
    tool(SubagentTool(config))
}

internal fun ToolScope.registerSubagentBuiltinCliTools() {
    tool(BashTool)
    tool(ReadTool)
}

@Serializable
internal data class BashInput(
    val command: String,
)

internal object BashTool : Tool<BashInput> {
    override val name: String = BashCommandLine.toolName
    override val description: String =
        "Run a shell command from the current working directory. " +
            "Current operating system: $currentOperatingSystemName. " +
            "Current shell: ${BashCommandLine.shellName}. " +
            "${BashCommandLine.commandSyntaxGuidance} " +
            "Use this for inspecting the project, running tests, and other shell tasks. " +
            "The command's stdout and stderr are returned, with long output truncated."
    override val inputSerializer = BashInput.serializer()
    override val hasSideEffects: Boolean = true

    override suspend fun execute(input: BashInput): String = executeToolSafely(name) {
        val command = input.command.trim()
        if (command.isEmpty()) return@executeToolSafely "Error: command is required."

        val result = NativeCliIo.runBash(command, MAX_BASH_OUTPUT_CHARS)
        val statusMark = if (result.status == 0) "✓" else "✗"
        return@executeToolSafely buildString {
            appendLine("$statusMark exit ${result.status}")
            val body = result.output.trimEnd()
            if (body.isNotEmpty()) {
                append(body)
                appendLine()
            }
            if (result.truncated) {
                appendLine(
                    "[truncated to $MAX_BASH_OUTPUT_CHARS of ${result.totalOutputChars} characters]"
                )
            }
        }.trimEnd()
    }
}

@Serializable
internal data class ReadInput(
    val path: String,
    val offset: Int? = null,
    val limit: Int? = null,
)

internal object ReadTool : Tool<ReadInput> {
    override val name: String = "Read"
    override val description: String =
        "Read a code or text file and return content with line numbers. " +
            "`path` may be relative to the current working directory. " +
            "`offset` is a 1-based starting line, and `limit` is the number of lines to read. " +
            "When no range is provided and the file is large, only total line and character counts are returned."
    override val inputSerializer = ReadInput.serializer()

    override suspend fun execute(input: ReadInput): String = executeToolSafely(name) {
        val path = input.path.trim()
        if (path.isEmpty()) return@executeToolSafely "Error: path is required."

        val hasExplicitWindow = input.offset != null || input.limit != null
        val offset = input.offset ?: 1
        if (offset < 1) return@executeToolSafely "Error: offset must be 1 or greater."

        val requestedLimit = input.limit ?: if (hasExplicitWindow) DEFAULT_READ_WINDOW_LINES else MAX_AUTO_READ_LINES
        if (requestedLimit < 1) return@executeToolSafely "Error: limit must be 1 or greater."

        val effectiveLimit = requestedLimit.coerceAtMost(MAX_READ_WINDOW_LINES)
        val maxChars = if (hasExplicitWindow) MAX_READ_WINDOW_CHARS else MAX_AUTO_READ_CHARS
        val scan = NativeCliIo.readTextWindow(
            path = path,
            offset = offset,
            limit = effectiveLimit,
            maxCapturedChars = maxChars,
        )

        if (scan.error != null) return@executeToolSafely "Error: ${scan.error}"

        if (!hasExplicitWindow && scan.isTooLargeForAutomaticRead()) {
            return@executeToolSafely buildLargeFileSummary(path, scan)
        }

        return@executeToolSafely buildReadOutput(
            path = path,
            scan = scan,
            offset = offset,
            requestedLimit = requestedLimit,
            effectiveLimit = effectiveLimit,
        )
    }

    private fun buildLargeFileSummary(path: String, scan: TextWindowScan): String = buildString {
        appendLine("${displayName(path)}  (${scan.totalLines} lines, ${scan.totalChars} chars)")
        append("File is too large to return automatically; ")
        append("use offset=1 and limit=$DEFAULT_READ_WINDOW_LINES to read a window.")
    }.trimEnd()

    private fun buildReadOutput(
        path: String,
        scan: TextWindowScan,
        offset: Int,
        requestedLimit: Int,
        effectiveLimit: Int,
    ): String = buildString {
        val firstLine = scan.lines.firstOrNull()?.number
        val lastLine = scan.lines.lastOrNull()?.number
        val name = displayName(path)
        if (firstLine == null || lastLine == null) {
            appendLine("$name  $offset-${offset + effectiveLimit - 1}")
            append("No lines in requested range.")
            return@buildString
        }

        appendLine("$name  $firstLine-$lastLine")
        if (requestedLimit != effectiveLimit) {
            appendLine("[requested limit $requestedLimit was capped to $effectiveLimit lines]")
        }
        if (scan.truncatedByChars) {
            appendLine("[content truncated at $MAX_READ_WINDOW_CHARS characters]")
        }
        append(formatNumberedLines(scan.lines, scan.totalLines))
    }.trimEnd()

    private fun TextWindowScan.isTooLargeForAutomaticRead(): Boolean =
        totalLines > MAX_AUTO_READ_LINES ||
            totalChars > MAX_AUTO_READ_CHARS ||
            truncatedByChars
}

@Serializable
internal data class WriteInput(
    val path: String,
    val content: String,
)

internal object WriteTool : Tool<WriteInput> {
    override val name: String = "Write"
    override val description: String =
        "Create a new file, or overwrite an existing file, with the given `content`. " +
            "`path` may be relative to the current working directory and parent directories must already exist. " +
            "Use this to author whole files; to change part of an existing file prefer the `Edit` tool. " +
            "The write is atomic and returns a short confirmation with the file's line and byte counts."
    override val inputSerializer = WriteInput.serializer()
    override val hasSideEffects: Boolean = true

    override suspend fun execute(input: WriteInput): String = executeToolSafely(name) {
        val path = input.path.trim()
        if (path.isEmpty()) return@executeToolSafely "Error: path is required."
        if (input.content.length > MAX_WRITE_FILE_CHARS) {
            return@executeToolSafely "Error: content exceeds the $MAX_WRITE_FILE_CHARS character limit; split the work or use the shell tool."
        }

        val existed = NativeCliIo.fileExists(path)
        val write = NativeCliIo.writeWholeFile(path, input.content)
        if (write.error != null) return@executeToolSafely "Error: ${write.error}"

        val lineCount = when {
            input.content.isEmpty() -> 0
            input.content.endsWith("\n") -> input.content.count { it == '\n' }
            else -> input.content.count { it == '\n' } + 1
        }
        val verb = if (existed) "Overwrote" else "Created"
        return@executeToolSafely "✓ $verb ${displayName(path)}  ($lineCount lines, ${write.bytesWritten} bytes)"
    }
}

@Serializable
internal data class EditInput(
    val path: String,
    val oldString: String,
    val newString: String,
    val replaceAll: Boolean = false,
)

internal object EditTool : Tool<EditInput> {
    override val name: String = "Edit"
    override val description: String =
        "Replace an exact text fragment in an existing file. Read the file first, then pass the " +
            "verbatim `oldString` to replace and the `newString` to insert. " +
            "`oldString` must match the file content exactly (including indentation) and must be " +
            "unique unless `replaceAll` is true. Provide enough surrounding context to make it unique. " +
            "Line endings are matched using `\\n` and the file's original CRLF/LF style is preserved. " +
            "To create a new file use the `Write` tool instead."
    override val inputSerializer = EditInput.serializer()
    override val hasSideEffects: Boolean = true

    override suspend fun execute(input: EditInput): String = executeToolSafely(name) {
        val path = input.path.trim()
        if (path.isEmpty()) return@executeToolSafely "Error: path is required."
        if (input.oldString.isEmpty()) {
            return@executeToolSafely "Error: oldString is required. To create a new file, use the Write tool."
        }
        if (input.oldString == input.newString) {
            return@executeToolSafely "Error: oldString and newString are identical; nothing to change."
        }

        val read = NativeCliIo.readWholeFile(path, MAX_EDIT_FILE_BYTES)
        if (read.error != null) return@executeToolSafely "Error: ${read.error}"
        val original = read.text ?: return@executeToolSafely "Error: unable to read file: $path"

        val useCrlf = original.contains("\r\n")
        val normalized = if (useCrlf) original.replace("\r\n", "\n") else original

        val occurrences = countOccurrences(normalized, input.oldString)
        if (occurrences == 0) {
            return@executeToolSafely "Error: oldString was not found in $path. " +
                "Read the file again and copy the exact text (including whitespace)."
        }
        if (occurrences > 1 && !input.replaceAll) {
            return@executeToolSafely "Error: oldString matched $occurrences locations in $path. " +
                "Add more surrounding context to make it unique, or set replace_all=true."
        }

        val updated = if (input.replaceAll) {
            normalized.replace(input.oldString, input.newString)
        } else {
            normalized.replaceFirst(input.oldString, input.newString)
        }

        val toWrite = if (useCrlf) updated.replace("\n", "\r\n") else updated
        val write = NativeCliIo.writeWholeFile(path, toWrite)
        if (write.error != null) return@executeToolSafely "Error: ${write.error}"

        val replacements = if (input.replaceAll) occurrences else 1
        return@executeToolSafely buildEditSummary(path, updated, input.newString, replacements)
    }

    private fun buildEditSummary(
        path: String,
        updated: String,
        newString: String,
        replacements: Int,
    ): String = buildString {
        val plural = if (replacements == 1) "replacement" else "replacements"
        appendLine("✓ Edited ${displayName(path)}  ($replacements $plural)")

        val index = if (newString.isEmpty()) -1 else updated.indexOf(newString)
        if (index < 0) return@buildString

        val startLine = updated.substring(0, index).count { it == '\n' } + 1
        val previewLineCount = (newString.count { it == '\n' } + 1).coerceAtMost(EDIT_PREVIEW_MAX_LINES)
        val allLines = updated.split("\n")
        val from = (startLine - 1).coerceIn(0, allLines.size)
        val to = (from + previewLineCount).coerceAtMost(allLines.size)
        if (from >= to) return@buildString

        val width = to.toString().length
        for (i in from until to) {
            appendLine("${(i + 1).toString().padStart(width)} | ${allLines[i].take(EDIT_PREVIEW_MAX_LINE_CHARS)}")
        }
    }.trimEnd()

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            count += 1
            from = at + needle.length
        }
        return count
    }
}

/**
 * Keep failures in a tool invocation on the agent's explicit error channel. Native
 * implementations can throw [Throwable] values that the framework's generic
 * exception boundary cannot classify, so catch them at the tool boundary and keep
 * the original stack trace in the resulting [AgentError].
 */
internal suspend fun executeToolSafely(
    toolName: String,
    block: suspend () -> String,
): String = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: AgentFrameworkException) {
    throw failure
} catch (failure: Throwable) {
    val type = failure::class.simpleName ?: failure::class.qualifiedName ?: "Throwable"
    val message = failure.message?.takeIf { it.isNotBlank() }
    val detail = if (message == null) "$type" else "$type: $message"
    val report = buildString {
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

private fun displayName(path: String): String =
    path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

private fun formatNumberedLines(lines: List<NumberedTextLine>, totalLines: Long): String {
    val width = maxOf(totalLines.toString().length, lines.lastOrNull()?.number?.toString()?.length ?: 1)
    return lines.joinToString("\n") { line ->
        "${line.number.toString().padStart(width)} | ${line.text}"
    }
}

private const val DEFAULT_READ_WINDOW_LINES = 500
private const val MAX_AUTO_READ_LINES = 500
private const val MAX_READ_WINDOW_LINES = 500
private const val MAX_AUTO_READ_CHARS = 100_000
private const val MAX_READ_WINDOW_CHARS = 100_000
private const val MAX_BASH_OUTPUT_CHARS = 100_000
private const val MAX_EDIT_FILE_BYTES = 1_000_000L
private const val MAX_WRITE_FILE_CHARS = 1_000_000
private const val EDIT_PREVIEW_MAX_LINES = 20
private const val EDIT_PREVIEW_MAX_LINE_CHARS = 200

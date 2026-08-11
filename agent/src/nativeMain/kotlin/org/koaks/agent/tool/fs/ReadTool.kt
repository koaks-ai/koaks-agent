package org.koaks.agent.tool.fs

import kotlinx.serialization.Serializable
import org.koaks.agent.platform.NativeFileSystem
import org.koaks.agent.platform.TextWindowScan
import org.koaks.agent.tool.DEFAULT_READ_WINDOW_LINES
import org.koaks.agent.tool.MAX_AUTO_READ_CHARS
import org.koaks.agent.tool.MAX_AUTO_READ_LINES
import org.koaks.agent.tool.MAX_READ_WINDOW_CHARS
import org.koaks.agent.tool.MAX_READ_WINDOW_LINES
import org.koaks.agent.tool.displayName
import org.koaks.agent.tool.executeToolSafely
import org.koaks.agent.tool.formatNumberedLines
import org.koaks.agent.tool.policy.WorkspaceAccessPolicy
import org.koaks.framework.tool.Tool
import org.koaks.runtime.resource.AccessMode
import org.koaks.runtime.resource.withRuntimeResource

@Serializable
internal data class ReadInput(
    val path: String,
    val offset: Int? = null,
    val limit: Int? = null,
)

internal class ReadTool(
    private val policy: WorkspaceAccessPolicy = WorkspaceAccessPolicy(),
) : Tool<ReadInput> {
    override val name: String = "Read"
    override val description: String =
        "Read a code or text file and return content with line numbers. " +
            "`path` may be relative to the current working directory. " +
            "`offset` is a 1-based starting line, and `limit` is the number of lines to read. " +
            "When no range is provided and the file is large, only total line and character counts are returned."
    override val inputSerializer = ReadInput.serializer()

    override suspend fun execute(input: ReadInput): String =
        executeToolSafely(name) {
            val requestedPath = input.path.trim()
            if (requestedPath.isEmpty()) return@executeToolSafely "Error: path is required."
            val path = policy.resolveRead(requestedPath)

            val hasExplicitWindow = input.offset != null || input.limit != null
            val offset = input.offset ?: 1
            if (offset < 1) return@executeToolSafely "Error: offset must be 1 or greater."

            val requestedLimit = input.limit ?: if (hasExplicitWindow) DEFAULT_READ_WINDOW_LINES else MAX_AUTO_READ_LINES
            if (requestedLimit < 1) return@executeToolSafely "Error: limit must be 1 or greater."

            val effectiveLimit = requestedLimit.coerceAtMost(MAX_READ_WINDOW_LINES)
            val maxChars =
                minOf(
                    if (hasExplicitWindow) MAX_READ_WINDOW_CHARS else MAX_AUTO_READ_CHARS,
                    policy.maxReadChars,
                )
            val scan =
                withRuntimeResource("file:$path", AccessMode.READ) {
                    NativeFileSystem.readTextWindow(path, offset, effectiveLimit, maxChars)
                }

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

    private fun buildLargeFileSummary(
        path: String,
        scan: TextWindowScan,
    ): String =
        buildString {
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
    ): String =
        buildString {
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

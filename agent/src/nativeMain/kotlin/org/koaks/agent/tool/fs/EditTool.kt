package org.koaks.agent.tool.fs

import kotlinx.serialization.Serializable
import org.koaks.agent.platform.NativeFileSystem
import org.koaks.agent.tool.EDIT_PREVIEW_MAX_LINES
import org.koaks.agent.tool.EDIT_PREVIEW_MAX_LINE_CHARS
import org.koaks.agent.tool.MAX_EDIT_FILE_BYTES
import org.koaks.agent.tool.displayName
import org.koaks.agent.tool.executeToolSafely
import org.koaks.agent.tool.policy.WorkspaceAccessPolicy
import org.koaks.framework.tool.Tool
import org.koaks.runtime.resource.AccessMode
import org.koaks.runtime.resource.withRuntimeResource

@Serializable
internal data class EditInput(
    val path: String,
    val oldString: String,
    val newString: String,
    val replaceAll: Boolean = false,
)

internal class EditTool(
    private val policy: WorkspaceAccessPolicy = WorkspaceAccessPolicy(),
) : Tool<EditInput> {
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

    override suspend fun execute(input: EditInput): String =
        executeToolSafely(name) {
            val requestedPath = input.path.trim()
            if (requestedPath.isEmpty()) return@executeToolSafely "Error: path is required."
            val path = policy.resolveRead(requestedPath)
            if (input.oldString.isEmpty()) {
                return@executeToolSafely "Error: oldString is required. To create a new file, use the Write tool."
            }
            if (input.oldString == input.newString) {
                return@executeToolSafely "Error: oldString and newString are identical; nothing to change."
            }

            val read =
                withRuntimeResource("file:$path", AccessMode.READ) {
                    NativeFileSystem.readWholeFile(path, minOf(MAX_EDIT_FILE_BYTES, policy.maxWriteChars.toLong()))
                }
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

            val updated =
                if (input.replaceAll) {
                    normalized.replace(input.oldString, input.newString)
                } else {
                    normalized.replaceFirst(input.oldString, input.newString)
                }

            val toWrite = if (useCrlf) updated.replace("\n", "\r\n") else updated
            val write =
                withRuntimeResource("file:$path", AccessMode.WRITE) {
                    NativeFileSystem.writeWholeFile(path, toWrite)
                }
            if (write.error != null) return@executeToolSafely "Error: ${write.error}"

            val replacements = if (input.replaceAll) occurrences else 1
            return@executeToolSafely buildEditSummary(path, updated, input.newString, replacements)
        }

    private fun buildEditSummary(
        path: String,
        updated: String,
        newString: String,
        replacements: Int,
    ): String =
        buildString {
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

    private fun countOccurrences(
        haystack: String,
        needle: String,
    ): Int {
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

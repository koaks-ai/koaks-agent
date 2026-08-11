package org.koaks.agent.tool

import org.koaks.agent.platform.NativeCliIo
import org.koaks.agent.platform.currentOperatingSystemName
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.AgentFrameworkException

public class WorkspaceAccessPolicy public constructor(
    root: String = NativeCliIo.workingDirectory(),
    public val maxReadChars: Int = 100_000,
    public val maxWriteChars: Int = 1_000_000,
) {
    private val canonicalRoot =
        NativeCliIo.canonicalPath(root)?.normalizeSeparators()
            ?: root.normalizeAbsolute()
    private val caseInsensitive = currentOperatingSystemName == "Windows"

    public fun resolveRead(path: String): String = resolve(path, mustExist = true)

    public fun resolveWrite(path: String): String = resolve(path, mustExist = false)

    private fun resolve(
        path: String,
        mustExist: Boolean,
    ): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) fail("path is required")
        val lexical = if (trimmed.isAbsolutePath()) trimmed.normalizeAbsolute() else "$canonicalRoot/$trimmed".normalizeAbsolute()
        ensureWithinRoot(lexical)
        if (mustExist && !NativeCliIo.fileExists(lexical)) fail("unable to resolve path: $path")

        val canonical =
            NativeCliIo.canonicalPath(lexical)
                ?: if (mustExist) fail("unable to resolve path: $path") else canonicalizeNewPath(lexical)
        ensureWithinRoot(canonical)
        return canonical
    }

    private fun canonicalizeNewPath(path: String): String {
        val parent = path.substringBeforeLast('/', missingDelimiterValue = canonicalRoot)
        val name = path.substringAfterLast('/')
        val canonicalParent =
            NativeCliIo.canonicalPath(parent)
                ?: fail("parent directory does not exist: $parent")
        return "${canonicalParent.normalizeSeparators().trimEnd('/')}/$name"
    }

    private fun ensureWithinRoot(path: String) {
        val root = canonicalRoot.trimEnd('/')
        val candidate = path.normalizeSeparators().trimEnd('/')
        val matches =
            if (caseInsensitive) {
                candidate.equals(root, ignoreCase = true) || candidate.startsWith("$root/", ignoreCase = true)
            } else {
                candidate == root || candidate.startsWith("$root/")
            }
        if (!matches) fail("path escapes workspace root: $path")
    }

    private fun fail(message: String): Nothing =
        throw AgentFrameworkException(
            AgentError.ToolError("WorkspaceAccessPolicy", message, retriable = false),
        )
}

public data class ProcessPolicy public constructor(
    public val maxCommandChars: Int = 100_000,
    public val maxOutputChars: Int = 100_000,
    public val timeoutMillis: Long = 120_000,
)

private fun String.isAbsolutePath(): Boolean =
    startsWith('/') || startsWith('\\') || (length >= 3 && this[1] == ':' && (this[2] == '/' || this[2] == '\\'))

private fun String.normalizeAbsolute(): String {
    val normalized = normalizeSeparators()
    val prefix =
        when {
            normalized.length >= 3 && normalized[1] == ':' -> normalized.take(3)
            normalized.startsWith('/') -> "/"
            else -> ""
        }
    val body = normalized.removePrefix(prefix)
    val segments = mutableListOf<String>()
    body.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." ->
                if (segments.isEmpty()) {
                    throw AgentFrameworkException(AgentError.ToolError("WorkspaceAccessPolicy", "path escapes workspace root", false))
                } else {
                    segments.removeAt(segments.lastIndex)
                }
            else -> segments += segment
        }
    }
    return prefix + segments.joinToString("/")
}

private fun String.normalizeSeparators(): String = replace('\\', '/')

@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.koaks.agent.platform

public interface Environment {
    public fun get(key: String): String?
}

public expect object PlatformEnvironment : Environment

public fun Environment.value(key: String): String? = get(key)?.trim()?.takeIf { it.isNotEmpty() }

internal data class CommandResult public constructor(
    public val status: Int,
    public val output: String,
    public val totalOutputChars: Int,
    public val truncated: Boolean,
)

internal data class NumberedTextLine public constructor(
    public val number: Long,
    public val text: String,
)

internal data class TextWindowScan public constructor(
    public val totalLines: Long,
    public val totalChars: Long,
    public val lines: List<NumberedTextLine>,
    public val truncatedByChars: Boolean,
    public val error: String? = null,
)

internal data class FileContent public constructor(
    public val text: String?,
    public val totalBytes: Long,
    public val error: String? = null,
)

internal data class FileWriteResult public constructor(
    public val bytesWritten: Long,
    public val error: String? = null,
)

internal expect object PlatformFileSystem {
    public fun workingDirectory(): String

    public fun canonicalPath(path: String): String?

    public fun renamePath(
        from: String,
        to: String,
    ): Boolean

    public fun removePath(path: String): Boolean

    public fun createDirectory(path: String): Boolean

    public fun timestamp(): Long

    public fun readTextWindow(
        path: String,
        offset: Int,
        limit: Int,
        maxCapturedChars: Int,
    ): TextWindowScan

    public fun fileExists(path: String): Boolean

    public fun readWholeFile(
        path: String,
        maxBytes: Long,
    ): FileContent

    public fun writeWholeFile(
        path: String,
        content: String,
    ): FileWriteResult
}

internal expect object PlatformPathResolver {
    public fun canonicalPath(path: String): String?
}

internal expect object BashCommandLine {
    public val toolName: String
    public val shellName: String
    public val commandSyntaxGuidance: String

    public fun execute(
        command: String,
        maxOutputChars: Int,
        timeoutMillis: Long,
    ): CommandResult
}

internal object PlatformProcess {
    public fun runShell(
        command: String,
        maxOutputChars: Int,
        timeoutMillis: Long = 120_000,
    ): CommandResult = BashCommandLine.execute(command, maxOutputChars, timeoutMillis)
}

public expect object PlatformInfo {
    public val operatingSystemName: String

    public fun workingDirectory(): String
}

internal val currentOperatingSystemName: String
    get() = PlatformInfo.operatingSystemName

internal expect object ConfigFileSystem {
    public fun createDirectory(path: String): Boolean
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.agent.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.remove
import platform.posix.system
import kotlin.random.Random

internal actual object BashCommandLine {
    public actual val toolName: String = "Bash"
    public actual val shellName: String = "Bash (`bash`)"
    public actual val commandSyntaxGuidance: String = "Use Bash command syntax."

    public actual fun execute(
        command: String,
        maxOutputChars: Int,
        timeoutMillis: Long,
    ): CommandResult {
        val token = Random.nextInt(0, Int.MAX_VALUE)
        val outputPath = ".koaks-bash-output-$token.log"
        val timeoutPath = ".koaks-bash-timeout-$token"
        val timeoutSeconds = ((timeoutMillis.coerceAtLeast(1) + 999) / 1000)
        val script =
            buildString {
                append("env -i PATH=\"${'$'}PATH\" HOME=\"${'$'}HOME\" TMPDIR=\"${'$'}{TMPDIR:-/tmp}\" bash -lc ")
                append(singleQuote(command)).append(" > ").append(singleQuote(outputPath)).append(" 2>&1 & pid=${'$'}!; ")
                append("(sleep ").append(timeoutSeconds).append("; echo timeout > ").append(singleQuote(timeoutPath))
                append("; kill -TERM ${'$'}pid 2>/dev/null) & watchdog=${'$'}!; ")
                append("wait ${'$'}pid; status=${'$'}?; kill ${'$'}watchdog 2>/dev/null; ")
                append("if [ -f ").append(singleQuote(timeoutPath)).append(" ]; then exit 124; else exit ${'$'}status; fi")
            }
        val rawStatus = system(script)
        val status = if (rawStatus < 0) 1 else (rawStatus shr 8) and 0xff
        val output = readOutput(outputPath, maxOutputChars)
        remove(outputPath)
        remove(timeoutPath)
        return CommandResult(
            status = status,
            output =
                output.text.ifEmpty {
                    if (status == 0) "" else "Unable to read command output. Make sure $shellName is installed and available on PATH."
                },
            totalOutputChars = output.totalChars,
            truncated = output.truncated,
        )
    }
}

private fun singleQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

private fun readOutput(
    path: String,
    maxChars: Int,
): AppleRawOutput {
    val file = fopen(path, "rb") ?: return AppleRawOutput("", 0, false)
    val captured = StringBuilder()
    var totalChars = 0
    var truncated = false
    try {
        memScoped {
            val buffer = allocArray<ByteVar>(8192)
            while (fgets(buffer, 8192, file) != null) {
                val chunk = buffer.toKString()
                totalChars += chunk.length
                val remaining = maxChars - captured.length
                if (remaining > 0) captured.append(chunk.take(remaining))
                if (chunk.length > remaining) truncated = true
            }
        }
    } finally {
        fclose(file)
    }
    return AppleRawOutput(captured.toString(), totalChars, truncated)
}

private data class AppleRawOutput(
    val text: String,
    val totalChars: Int,
    val truncated: Boolean,
)

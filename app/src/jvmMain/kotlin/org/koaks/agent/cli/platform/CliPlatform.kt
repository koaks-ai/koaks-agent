package org.koaks.agent.cli.platform

import org.koaks.agent.cli.trace.TraceWriter
import org.koaks.agent.platform.Environment
import org.koaks.agent.platform.PlatformEnvironment
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal actual object CliPlatform {
    actual val environment: Environment = PlatformEnvironment

    actual fun writeLine(text: String) = println(text)

    actual fun openTraceWriter(path: String): TraceWriter? = JvmTraceWriter.open(path)
}

private class JvmTraceWriter private constructor(
    private val path: Path,
) : TraceWriter {
    override fun write(line: String) {
        Files.write(
            path,
            line.toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        )
    }

    override fun close() = Unit

    companion object {
        fun open(path: String): TraceWriter? = runCatching { JvmTraceWriter(Path.of(path)) }.getOrNull()
    }
}

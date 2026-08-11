@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.agent.cli.platform

import kotlinx.cinterop.CPointer
import org.koaks.agent.cli.trace.TraceWriter
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fputs

internal class PosixTraceWriter private constructor(
    private val file: CPointer<FILE>,
) : TraceWriter {
    override fun write(line: String) {
        fputs(line, file)
        fflush(file)
    }

    override fun close() {
        fclose(file)
    }

    companion object {
        fun open(path: String): TraceWriter? = fopen(path, "ab")?.let(::PosixTraceWriter)
    }
}

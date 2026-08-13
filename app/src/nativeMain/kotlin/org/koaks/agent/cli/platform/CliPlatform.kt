@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.koaks.agent.cli.platform

import org.koaks.agent.cli.trace.TraceWriter
import org.koaks.agent.platform.Environment
import org.koaks.agent.platform.PlatformEnvironment

internal actual object CliPlatform {
    actual val environment: Environment = PlatformEnvironment

    actual fun writeLine(text: String) = NativeConsole.writeLine(text)

    actual fun openTraceWriter(path: String): TraceWriter? = PosixTraceWriter.open(path)
}

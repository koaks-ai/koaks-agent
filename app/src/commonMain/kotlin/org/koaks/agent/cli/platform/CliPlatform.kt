@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.koaks.agent.cli.platform

import org.koaks.agent.cli.trace.TraceWriter
import org.koaks.agent.platform.Environment

internal expect object CliPlatform {
    val environment: Environment

    fun writeLine(text: String)

    fun openTraceWriter(path: String): TraceWriter?
}

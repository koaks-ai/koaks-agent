@file:OptIn(ExperimentalNativeApi::class)

package org.koaks.agent

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import org.koaks.agent.app.AgentApp
import org.koaks.agent.app.printFatal
import org.koaks.agent.config.ArgParser
import org.koaks.agent.config.CliException
import org.koaks.agent.config.ConfigResolver
import org.koaks.agent.config.PosixEnvironment
import org.koaks.agent.config.usageText
import org.koaks.agent.tui.Output
import org.koaks.agent.tui.StdoutOutput

fun main(args: Array<String>) {
    setUnhandledExceptionHook { error ->
        // The process is terminated immediately after this hook returns. Route the
        // report through Output so it is flushed before native termination; a bare
        // println can remain buffered and make a crash look silent.
        printFatal(StdoutOutput(), error)
        terminateWithUnhandledException(error)
    }

    runBlocking {
        val output = StdoutOutput()
        val options = try {
            ArgParser.parse(args)
        } catch (e: CliException) {
            printError(output, e.message ?: "Invalid arguments.")
            output.writeLine(usageText())
            return@runBlocking
        }

        if (options.showHelp) {
            output.writeLine(usageText())
            return@runBlocking
        }

        val config = try {
            ConfigResolver.resolve(PosixEnvironment)
        } catch (e: CliException) {
            printError(output, e.message ?: "Invalid configuration.")
            output.writeLine(usageText())
            return@runBlocking
        }

        try {
            AgentApp(initialConfig = config, output = output).run()
        } catch (t: Throwable) {
            printFatal(output, t)
            exitProcess(1)
        }
    }
}

private fun printError(output: Output, message: String) {
    output.writeLine("[error] $message")
}

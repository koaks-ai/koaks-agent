@file:OptIn(ExperimentalNativeApi::class)

package org.koaks.agent.bootstrap

import kotlinx.coroutines.runBlocking
import org.koaks.agent.app.TerminalConsole
import org.koaks.agent.app.TerminalFrontend
import org.koaks.agent.app.TerminalToolApproval
import org.koaks.agent.config.ArgParser
import org.koaks.agent.config.CliCommand
import org.koaks.agent.config.CliException
import org.koaks.agent.config.ConfigResolver
import org.koaks.agent.config.initializeConfig
import org.koaks.agent.config.resolve
import org.koaks.agent.config.usageText
import org.koaks.agent.credential.PlatformCredentialResolver
import org.koaks.agent.credential.ToolApproval
import org.koaks.agent.definition.AgentDefinitionFactory
import org.koaks.agent.platform.PosixEnvironment
import org.koaks.agent.session.CliChatSession
import org.koaks.agent.session.CliTrace
import org.koaks.runtime.AgentRuntime
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException
import kotlin.system.exitProcess

public fun main(args: Array<String>) {
    setUnhandledExceptionHook { error ->
        // The process is terminated immediately after this hook returns. Route the
        // report through Output so it is flushed before native termination; a bare
        // println can remain buffered and make a crash look silent.
        TerminalConsole.printFatal(error)
        terminateWithUnhandledException(error)
    }

    runBlocking {
        val options =
            try {
                ArgParser.parse(args)
            } catch (e: CliException) {
                printError(e.message ?: "Invalid arguments.")
                TerminalConsole.writeLine(usageText())
                return@runBlocking
            }

        if (options.showHelp) {
            TerminalConsole.writeLine(usageText())
            return@runBlocking
        }

        if (options.command == CliCommand.INIT) {
            try {
                val result = initializeConfig(PosixEnvironment, options.force)
                TerminalConsole.writeLine("Created ${result.path}")
                result.backupPath?.let { TerminalConsole.writeLine("Backed up previous config to $it") }
            } catch (e: CliException) {
                printError(e.message ?: "Unable to initialize configuration.")
            }
            return@runBlocking
        }

        val config =
            try {
                ConfigResolver.resolve(PosixEnvironment)
            } catch (e: CliException) {
                printError(e.message ?: "Invalid configuration.")
                TerminalConsole.writeLine(usageText())
                return@runBlocking
            }

        val trace = CliTrace.open(PosixEnvironment)
        try {
            val approval = TerminalToolApproval()
            val definitions =
                AgentDefinitionFactory(
                    credentials = PlatformCredentialResolver(PosixEnvironment),
                    toolApproval = ToolApproval(approval::request),
                )
            AgentRuntime { maxConcurrency = DEFAULT_MAX_CONCURRENCY }.use { runtime ->
                CliChatSession(config, runtime, definitions, trace.takeIf { it.enabled }).use { session ->
                    TerminalFrontend(
                        session = session,
                        trace = trace,
                        environment = PosixEnvironment,
                        toolApproval = approval,
                    ).run()
                }
            }
        } catch (t: Throwable) {
            TerminalConsole.printFatal(t)
            exitProcess(1)
        } finally {
            trace.close()
        }
    }
}

private const val DEFAULT_MAX_CONCURRENCY = 8

private fun printError(message: String): Unit = TerminalConsole.writeLine("[error] $message")

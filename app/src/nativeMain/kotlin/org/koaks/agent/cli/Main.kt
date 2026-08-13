@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package org.koaks.agent.cli

import kotlinx.coroutines.runBlocking
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException
import kotlin.system.exitProcess

public fun main(args: Array<String>) {
    setUnhandledExceptionHook { error ->
        TerminalConsole.printFatal(error)
        terminateWithUnhandledException(error)
    }
    exitProcess(runBlocking { runCli(args) })
}

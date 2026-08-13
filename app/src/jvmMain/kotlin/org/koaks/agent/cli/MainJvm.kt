package org.koaks.agent.cli

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

public fun main(args: Array<String>) {
    exitProcess(runBlocking { runCli(args) })
}

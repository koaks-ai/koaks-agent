package org.koaks.agent.cli

import org.koaks.agent.cli.platform.CliPlatform
import org.koaks.agent.cli.trace.CliTrace
import org.koaks.agent.config.ConfigException
import org.koaks.agent.config.ConfigFailure
import org.koaks.agent.config.ConfigResolver
import org.koaks.agent.config.initializeConfig
import org.koaks.agent.definition.AgentDefinitionFactory
import org.koaks.agent.definition.SetupFailure
import org.koaks.agent.platform.PlatformInfo
import org.koaks.agent.session.AgentChatSession
import org.koaks.agent.tool.approval.ToolApprovalPort
import org.koaks.agent.tool.policy.ProcessPolicy
import org.koaks.agent.tool.policy.WorkspaceAccessPolicy
import org.koaks.agent.tui.approval.TerminalToolApproval
import org.koaks.agent.tui.frontend.TerminalFrontend
import org.koaks.framework.memory.ThreadId
import org.koaks.framework.memory.WindowMemoryProvider
import org.koaks.runtime.AgentRuntime

public suspend fun runCli(args: Array<String>): Int {
    val environment = CliPlatform.environment
    val options =
        try {
            ArgParser.parse(args)
        } catch (e: CliUsageException) {
            printError(e.message ?: "Invalid arguments.")
            TerminalConsole.writeLine(usageText())
            return 0
        }

    if (options.showHelp) {
        TerminalConsole.writeLine(usageText())
        return 0
    }

    if (options.command == CliCommand.INIT) {
        return try {
            val result = initializeConfig(environment, options.force)
            TerminalConsole.writeLine("Created ${result.path}")
            result.backupPath?.let { TerminalConsole.writeLine("Backed up previous config to $it") }
            0
        } catch (e: ConfigException) {
            printError(e.failure.message())
            1
        }
    }

    val config =
        try {
            ConfigResolver.resolve(environment)
        } catch (e: ConfigException) {
            printError(e.failure.message())
            TerminalConsole.writeLine(usageText())
            return 1
        }

    val trace = CliTrace.open(environment)
    return try {
        val approval = TerminalToolApproval()
        val workspacePolicy = WorkspaceAccessPolicy(PlatformInfo.workingDirectory())
        val processPolicy = ProcessPolicy()
        val definitions =
            AgentDefinitionFactory(
                toolApproval = ToolApprovalPort(approval::request),
                workspacePolicy = workspacePolicy,
                processPolicy = processPolicy,
            )
        val memoryProvider = WindowMemoryProvider(config.memory.historyMessages)
        val threadId = ThreadId(config.session.defaultThreadId)
        AgentRuntime { maxConcurrency = DEFAULT_MAX_CONCURRENCY }.use { runtime ->
            AgentChatSession(
                config = config,
                threadId = threadId,
                runtime = runtime,
                definitions = definitions,
                memoryProvider = memoryProvider,
                listener = trace.takeIf { it.enabled },
            ).use { session ->
                TerminalFrontend(
                    session = session,
                    trace = trace,
                    environment = environment,
                    toolApproval = approval,
                    setupFailureMessage = SetupFailure::message,
                ).run()
            }
        }
        0
    } catch (t: Throwable) {
        TerminalConsole.printFatal(t)
        1
    } finally {
        trace.close()
    }
}

private const val DEFAULT_MAX_CONCURRENCY = 8

private fun printError(message: String): Unit = TerminalConsole.writeLine("[error] $message")

private fun ConfigFailure.message(): String =
    when (this) {
        is ConfigFailure.SchemaMismatch ->
            "Unsupported config schema '${found ?: "missing"}'. " +
                "Run 'koaks init --force' to create schema $expected."
        is ConfigFailure.Parse -> "$source:$line: $detail"
        is ConfigFailure.InvalidValue -> detail
        ConfigFailure.HomeUnavailable -> "Unable to find home directory for ~/.koaks/config.toml."
        is ConfigFailure.FileNotFound -> "Configuration file not found: $path. Run 'koaks init' first."
        is ConfigFailure.AlreadyExists -> "Configuration already exists: $path. Use 'koaks init --force' to replace it."
        is ConfigFailure.BackupFailed -> "Unable to back up existing configuration: $path"
        is ConfigFailure.InstallFailed -> "Unable to install configuration atomically: $path"
        is ConfigFailure.SkillPathHomeUnavailable -> "Unable to expand Skill path '$path': home directory is unavailable."
        is ConfigFailure.UnsupportedSkillPath ->
            "Unsupported Skill path '$path': only '~/' and '~\\' home expansion are supported."
        is ConfigFailure.CreateFailed -> "Unable to create config file: $path"
        is ConfigFailure.WriteFailed -> "Unable to write config file: $path"
    }

private fun SetupFailure.message(): String =
    when (this) {
        is SetupFailure.MissingApiKey -> "Missing api_key for ${provider.id}."
        is SetupFailure.MissingProviderBinding -> "No ProviderBinding registered for ${provider.id}."
    }

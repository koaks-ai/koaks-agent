package org.koaks.agent.tool

import org.koaks.agent.tool.delegate.SubagentFactory
import org.koaks.agent.tool.delegate.SubagentTool
import org.koaks.agent.tool.fs.EditTool
import org.koaks.agent.tool.fs.ReadTool
import org.koaks.agent.tool.fs.WriteTool
import org.koaks.agent.tool.policy.ProcessPolicy
import org.koaks.agent.tool.policy.WorkspaceAccessPolicy
import org.koaks.agent.tool.shell.ShellTool
import org.koaks.framework.loop.ToolScope
import org.koaks.framework.tool.Tool

internal class BuiltinToolSet(
    private val workspacePolicy: WorkspaceAccessPolicy = WorkspaceAccessPolicy(),
    private val processPolicy: ProcessPolicy = ProcessPolicy(),
) {
    internal fun subagentTools(): List<Tool<*>> =
        listOf(
            ShellTool(processPolicy),
            ReadTool(workspacePolicy),
        )

    internal fun mainTools(subagent: SubagentFactory): List<Tool<*>> =
        listOf(
            ShellTool(processPolicy),
            ReadTool(workspacePolicy),
            WriteTool(workspacePolicy),
            EditTool(workspacePolicy),
            SubagentTool(subagent),
        )
}

internal fun ToolScope.registerTools(tools: Iterable<Tool<*>>) {
    tools.forEach { tool(it) }
}

internal fun Iterable<Tool<*>>.sideEffectToolNames(): Set<String> = filter { it.hasSideEffects }.mapTo(linkedSetOf()) { it.name }

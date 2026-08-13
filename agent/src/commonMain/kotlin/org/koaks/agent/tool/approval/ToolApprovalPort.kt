package org.koaks.agent.tool.approval

public fun interface ToolApprovalPort {
    public suspend fun approve(
        toolName: String,
        arguments: String,
    ): Boolean
}

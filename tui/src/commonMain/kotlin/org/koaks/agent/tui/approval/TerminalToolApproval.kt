package org.koaks.agent.tui.approval

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bridges Koaks HumanApproval requests into the terminal frontend's input loop. */
public class TerminalToolApproval public constructor() {
    private val requestChannel = Channel<ToolApprovalRequest>(capacity = 1)
    private val sessionAllowancesLock = Mutex()
    private val sessionAllowedTools = mutableSetOf<String>()

    internal val requests: ReceiveChannel<ToolApprovalRequest>
        get() = requestChannel

    public suspend fun request(
        toolName: String,
        arguments: String,
    ): Boolean {
        if (sessionAllowancesLock.withLock { toolName in sessionAllowedTools }) return true

        val response = CompletableDeferred<ApprovalDecision>()
        requestChannel.send(ToolApprovalRequest(toolName, arguments, response))
        return when (response.await()) {
            ApprovalDecision.AllowOnce -> true
            ApprovalDecision.AllowForSession -> {
                sessionAllowancesLock.withLock { sessionAllowedTools += toolName }
                true
            }
            ApprovalDecision.Deny -> false
        }
    }
}

internal enum class ApprovalDecision {
    AllowOnce,
    AllowForSession,
    Deny,
}

internal data class ToolApprovalRequest(
    val toolName: String,
    val arguments: String,
    private val response: CompletableDeferred<ApprovalDecision>,
) {
    fun respond(decision: ApprovalDecision) {
        response.complete(decision)
    }
}

package org.koaks.agent.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** Bridges Koaks HumanApproval requests into the terminal frontend's input loop. */
public class TerminalToolApproval public constructor() {
    private val requestChannel = Channel<ToolApprovalRequest>(capacity = 1)

    internal val requests: ReceiveChannel<ToolApprovalRequest>
        get() = requestChannel

    public suspend fun request(
        toolName: String,
        arguments: String,
    ): Boolean {
        val response = CompletableDeferred<Boolean>()
        requestChannel.send(ToolApprovalRequest(toolName, arguments, response))
        return response.await()
    }
}

internal data class ToolApprovalRequest(
    val toolName: String,
    val arguments: String,
    private val response: CompletableDeferred<Boolean>,
) {
    fun respond(allowed: Boolean) {
        response.complete(allowed)
    }
}

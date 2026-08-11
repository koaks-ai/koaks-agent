package org.koaks.agent.app

import org.koaks.framework.loop.AgentEvent

public interface EventRenderTrace {
    public fun renderStage(
        event: AgentEvent,
        stage: String,
        renderedChars: Int? = null,
    )

    public fun markdownFallback(
        reason: String,
        state: String,
        pendingChars: Int,
        errorType: String?,
    )
}

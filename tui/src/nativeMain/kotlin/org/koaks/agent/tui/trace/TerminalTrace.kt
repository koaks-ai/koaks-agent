package org.koaks.agent.tui.trace

import org.koaks.framework.loop.AgentEvent

public interface TerminalTrace : EventRenderTrace {
    public val enabled: Boolean

    public fun turnStarted(inputLength: Int)

    public fun collectorStarted()

    public fun eventReceived(event: AgentEvent)

    public fun eventRendered(event: AgentEvent)

    public fun collectorCompleted()

    public fun collectorFailed(error: Throwable)
}

public object NoopTerminalTrace : TerminalTrace {
    override val enabled: Boolean = false

    override fun turnStarted(inputLength: Int): Unit = Unit

    override fun collectorStarted(): Unit = Unit

    override fun eventReceived(event: AgentEvent): Unit = Unit

    override fun eventRendered(event: AgentEvent): Unit = Unit

    override fun collectorCompleted(): Unit = Unit

    override fun collectorFailed(error: Throwable): Unit = Unit

    override fun renderStage(
        event: AgentEvent,
        stage: String,
        renderedChars: Int?,
    ): Unit = Unit

    override fun markdownFallback(
        reason: String,
        state: String,
        pendingChars: Int,
        errorType: String?,
    ): Unit = Unit
}

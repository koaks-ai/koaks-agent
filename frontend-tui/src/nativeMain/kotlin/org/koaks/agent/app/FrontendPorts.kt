package org.koaks.agent.app

import kotlinx.coroutines.flow.Flow
import org.koaks.agent.config.AgentConfig
import org.koaks.agent.config.SessionPreferences
import org.koaks.framework.loop.AgentEvent

public interface ChatSessionPort {
    public val config: AgentConfig

    public fun updatePreferences(transform: (SessionPreferences) -> SessionPreferences)

    public fun stream(input: String): Flow<AgentEvent>
}

public interface FrontendTrace : EventRenderTrace {
    public val enabled: Boolean

    public fun turnStarted(inputLength: Int)

    public fun collectorStarted()

    public fun eventReceived(event: AgentEvent)

    public fun eventRendered(event: AgentEvent)

    public fun collectorCompleted()

    public fun collectorFailed(error: Throwable)
}

internal object NoopFrontendTrace : FrontendTrace {
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

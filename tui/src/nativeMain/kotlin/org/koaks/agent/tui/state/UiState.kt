package org.koaks.agent.tui.state

import org.koaks.agent.session.SessionSnapshot
import org.koaks.framework.loop.AgentEvent

internal data class UiState(
    val snapshot: SessionSnapshot,
    val running: Boolean = false,
    val exitRequested: Boolean = false,
    val lastFailure: String? = null,
)

internal sealed interface UiAction {
    data class InputSubmitted(
        val input: String,
    ) : UiAction

    data class AgentEventReceived(
        val event: AgentEvent,
    ) : UiAction

    data object RunCompleted : UiAction

    data class RunFailed(
        val message: String,
    ) : UiAction

    data object ExitRequested : UiAction
}

internal sealed interface UiEffect {
    data class RunAgent(
        val input: String,
    ) : UiEffect
}

internal data class Reduction(
    val state: UiState,
    val effects: List<UiEffect> = emptyList(),
)

internal class UiReducer {
    fun reduce(
        state: UiState,
        action: UiAction,
    ): Reduction =
        when (action) {
            is UiAction.InputSubmitted ->
                Reduction(
                    state.copy(running = true, lastFailure = null),
                    listOf(UiEffect.RunAgent(action.input)),
                )
            is UiAction.AgentEventReceived ->
                when (action.event) {
                    is AgentEvent.Completed, is AgentEvent.Terminated -> Reduction(state.copy(running = false))
                    is AgentEvent.Failed ->
                        Reduction(
                            state.copy(running = false, lastFailure = action.event.error.message),
                        )
                    else -> Reduction(state)
                }
            UiAction.RunCompleted -> Reduction(state.copy(running = false))
            is UiAction.RunFailed -> Reduction(state.copy(running = false, lastFailure = action.message))
            UiAction.ExitRequested -> Reduction(state.copy(exitRequested = true, running = false))
        }
}

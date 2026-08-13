package org.koaks.agent.session

import kotlinx.coroutines.flow.Flow
import org.koaks.agent.provider.Provider
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.memory.ThreadId

public interface ChatSession {
    public val snapshot: SessionSnapshot

    public fun update(command: SessionCommand): SessionUpdateResult

    public fun stream(input: String): Flow<AgentEvent>
}

public sealed interface SessionCommand {
    public data class SelectProvider(
        val provider: Provider,
    ) : SessionCommand

    public data class SelectModel(
        val modelName: String,
    ) : SessionCommand

    public data class SetReasoning(
        val enabled: Boolean,
    ) : SessionCommand
}

public sealed interface SessionUpdateResult {
    public data class Updated(
        val snapshot: SessionSnapshot,
    ) : SessionUpdateResult

    public data class Rejected(
        val reason: SessionUpdateFailure,
    ) : SessionUpdateResult
}

public sealed interface SessionUpdateFailure {
    public data object BlankModel : SessionUpdateFailure

    public data class UnknownModel(
        val modelName: String,
        val expected: List<String>,
    ) : SessionUpdateFailure
}

public data class SessionSnapshot public constructor(
    public val provider: Provider,
    public val modelName: String,
    public val baseUrl: String,
    public val credential: CredentialSummary,
    public val threadId: ThreadId,
    public val historyMessages: Int,
    public val reasoningEnabled: Boolean,
    public val skillPaths: List<String>,
    public val skills: List<String>,
    public val availableProviders: List<Provider>,
    public val availableModels: List<String>,
)

public sealed interface CredentialSummary {
    public data object InlineConfigured : CredentialSummary

    public data object NotRequired : CredentialSummary
}

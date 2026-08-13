package org.koaks.agent.definition

import org.koaks.agent.provider.Provider

public sealed interface SetupFailure {
    public data class MissingApiKey(
        val provider: Provider,
    ) : SetupFailure

    public data class MissingProviderBinding(
        val provider: Provider,
    ) : SetupFailure
}

public class AgentSetupException public constructor(
    public val failure: SetupFailure,
) : IllegalStateException()

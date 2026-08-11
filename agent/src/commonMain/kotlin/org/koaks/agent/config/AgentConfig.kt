package org.koaks.agent.config

import org.koaks.agent.provider.Provider
import org.koaks.agent.provider.ProviderProfiles

internal const val DEFAULT_THREAD_ID = "koaks-agent"
internal const val DEFAULT_HISTORY_MESSAGES = 1024

public data class AgentConfig public constructor(
    public val model: ModelDefaults,
    public val providers: ProviderProfiles,
    public val prompt: PromptSettings,
    public val memory: MemorySettings,
    public val skills: SkillSettings,
    public val session: SessionDefaults,
)

public data class ModelDefaults public constructor(
    public val provider: Provider,
    public val modelName: String,
    public val temperature: Double?,
    public val reasoningEnabled: Boolean,
)

public data class PromptSettings public constructor(
    public val instructions: String,
)

public data class MemorySettings public constructor(
    public val historyMessages: Int,
)

public data class SkillSettings public constructor(
    public val paths: List<String>,
    public val enabled: List<String>,
)

public data class SessionDefaults public constructor(
    public val defaultThreadId: String,
)

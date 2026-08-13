package org.koaks.agent.tool.policy

public data class ProcessPolicy public constructor(
    public val maxCommandChars: Int = 100_000,
    public val maxOutputChars: Int = 100_000,
    public val timeoutMillis: Long = 120_000,
)

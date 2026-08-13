package org.koaks.agent.credential

public class ApiKey public constructor(
    public val value: String,
) {
    override fun toString(): String = "<redacted>"

    override fun equals(other: Any?): Boolean = other is ApiKey && value == other.value

    override fun hashCode(): Int = value.hashCode()
}

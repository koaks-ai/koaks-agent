package org.koaks.agent.config

public enum class CredentialSource private constructor() {
    ENVIRONMENT,
    SYSTEM,
    ;

    public companion object {
        public fun parse(value: String): CredentialSource =
            when (value.trim().lowercase()) {
                "environment", "env" -> ENVIRONMENT
                "system", "keychain", "credential_manager" -> SYSTEM
                else -> throw CliException("Unknown credential source '$value'. Expected environment or system.")
            }
    }
}

public data class CredentialRef public constructor(
    public val source: CredentialSource,
    public val name: String,
)

public class ApiKey public constructor(
    public val value: String,
) {
    override fun toString(): String = "<redacted>"

    override fun equals(other: Any?): Boolean = other is ApiKey && value == other.value

    override fun hashCode(): Int = value.hashCode()
}

package org.koaks.agent.config

public sealed interface ConfigFailure {
    public data class SchemaMismatch(
        val found: Int?,
        val expected: Int,
    ) : ConfigFailure

    public data class Parse(
        val source: String,
        val line: Int,
        val detail: String,
    ) : ConfigFailure

    public data class InvalidValue(
        val detail: String,
    ) : ConfigFailure

    public data object HomeUnavailable : ConfigFailure

    public data class FileNotFound(
        val path: String,
    ) : ConfigFailure

    public data class AlreadyExists(
        val path: String,
    ) : ConfigFailure

    public data class BackupFailed(
        val path: String,
    ) : ConfigFailure

    public data class InstallFailed(
        val path: String,
    ) : ConfigFailure

    public data class SkillPathHomeUnavailable(
        val path: String,
    ) : ConfigFailure

    public data class UnsupportedSkillPath(
        val path: String,
    ) : ConfigFailure

    public data class CreateFailed(
        val path: String,
    ) : ConfigFailure

    public data class WriteFailed(
        val path: String,
    ) : ConfigFailure
}

public class ConfigException public constructor(
    public val failure: ConfigFailure,
) : IllegalArgumentException()

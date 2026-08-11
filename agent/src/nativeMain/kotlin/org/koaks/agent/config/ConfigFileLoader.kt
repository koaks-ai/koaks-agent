package org.koaks.agent.config

import org.koaks.agent.platform.ConfigFileSystem
import org.koaks.agent.platform.Environment
import org.koaks.agent.platform.NativeFileSystem
import org.koaks.agent.platform.value
import org.koaks.agent.provider.Provider

internal object ConfigFileLoader {
    fun load(env: Environment): FileConfig {
        val path = configPath(env) ?: throw ConfigException(ConfigFailure.HomeUnavailable)
        val text =
            NativeFileSystem.readWholeFile(path, MAX_CONFIG_BYTES).text
                ?: throw ConfigException(ConfigFailure.FileNotFound(path))
        return TomlConfigParser.parse(text, path).let { config ->
            config.copy(skillPaths = config.skillPaths.map { expandSkillPath(it, env) })
        }
    }

    fun initialize(
        env: Environment,
        force: Boolean,
    ): ConfigInitResult {
        val path = configPath(env) ?: throw ConfigException(ConfigFailure.HomeUnavailable)
        val existing = NativeFileSystem.readWholeFile(path, MAX_CONFIG_BYTES).text
        if (existing != null && !force) {
            throw ConfigException(ConfigFailure.AlreadyExists(path))
        }

        parentDirectory(path)?.let(ConfigFileSystem::createDirectory)
        val temporaryPath = "$path.tmp"
        val write = NativeFileSystem.writeWholeFile(temporaryPath, defaultConfigText())
        if (write.error != null) {
            val failure =
                if (write.error.startsWith("unable to open file for writing")) {
                    ConfigFailure.CreateFailed(path)
                } else {
                    ConfigFailure.WriteFailed(path)
                }
            throw ConfigException(failure)
        }

        val backupPath = if (existing != null) "$path.bak-${NativeFileSystem.timestamp()}" else null
        if (backupPath != null && !NativeFileSystem.renamePath(path, backupPath)) {
            NativeFileSystem.removePath(temporaryPath)
            throw ConfigException(ConfigFailure.BackupFailed(path))
        }
        if (!NativeFileSystem.renamePath(temporaryPath, path)) {
            backupPath?.let { NativeFileSystem.renamePath(it, path) }
            NativeFileSystem.removePath(temporaryPath)
            throw ConfigException(ConfigFailure.InstallFailed(path))
        }
        return ConfigInitResult(path, backupPath)
    }

    fun configPath(env: Environment): String? {
        val home = env.value("HOME") ?: env.value("USERPROFILE") ?: return null
        return "${home.trimEnd('/', '\\')}/.koaks/config.toml"
    }

    internal fun defaultConfigText(): String =
        """
        # Koaks Agent configuration.
        schema_version = $CURRENT_CONFIG_SCHEMA_VERSION
        provider = "openai"
        # Enable model thinking / show reasoning output (same as /reasoning on|off).
        show_reasoning = false
        # Skill directories. When `skills` is omitted or empty, every discovered Skill is enabled.
        # skill_paths = [".agents/skills"]
        # skills = ["code-review", "project-conventions"]

        [providers.openai]
        base_url = "${Provider.OPENAI.defaultBaseUrl}"
        credential_source = "environment"
        credential_name = "OPENAI_API_KEY"
        # Alternatively, replace the two credential fields with: api_key = "..."
        model = "gpt-5.5"
        model_list = ["gpt-5.5"]

        [providers.anthropic]
        base_url = "${Provider.ANTHROPIC.defaultBaseUrl}"
        credential_source = "environment"
        credential_name = "ANTHROPIC_API_KEY"
        # Alternatively, replace the two credential fields with: api_key = "..."
        model = "claude-opus-4-8"
        model_list = ["claude-opus-4-8"]
        """.trimIndent() + "\n"

    internal fun expandSkillPath(
        path: String,
        env: Environment,
    ): String {
        if (path == "~" || path.startsWith("~/") || path.startsWith("~\\")) {
            val home =
                env.value("HOME") ?: env.value("USERPROFILE")
                    ?: throw ConfigException(ConfigFailure.SkillPathHomeUnavailable(path))
            return home.trimEnd('/', '\\') + path.drop(1)
        }
        if (path.startsWith('~')) {
            throw ConfigException(ConfigFailure.UnsupportedSkillPath(path))
        }
        return path
    }

    private fun parentDirectory(path: String): String? {
        val slashIndex = path.lastIndexOf('/')
        val backslashIndex = path.lastIndexOf('\\')
        val index = maxOf(slashIndex, backslashIndex)
        if (index <= 0) return null
        return path.take(index).takeIf { it.isNotBlank() }
    }
}

public data class ConfigInitResult public constructor(
    public val path: String,
    public val backupPath: String?,
)

private const val MAX_CONFIG_BYTES = 2_000_000L

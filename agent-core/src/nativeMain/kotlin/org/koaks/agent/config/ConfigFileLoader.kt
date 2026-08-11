@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.agent.config

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import org.koaks.agent.platform.ConfigFileSystem
import org.koaks.agent.platform.Environment
import org.koaks.agent.platform.value
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.remove
import platform.posix.rename
import platform.posix.time

internal object ConfigFileLoader {
    fun load(env: Environment): FileConfig {
        val path = configPath(env) ?: throw CliException("Unable to find home directory for ~/.koaks/config.toml.")
        val text =
            readText(path)
                ?: throw CliException("Configuration file not found: $path. Run 'koaks init' first.")
        return TomlConfigParser.parse(text, path).let { config ->
            config.copy(skillPaths = config.skillPaths.map { expandSkillPath(it, env) })
        }
    }

    fun initialize(
        env: Environment,
        force: Boolean,
    ): ConfigInitResult {
        val path = configPath(env) ?: throw CliException("Unable to find home directory for ~/.koaks/config.toml.")
        val existing = readText(path)
        if (existing != null && !force) {
            throw CliException("Configuration already exists: $path. Use 'koaks init --force' to replace it.")
        }

        parentDirectory(path)?.let(ConfigFileSystem::createDirectory)
        val temporaryPath = "$path.tmp"
        writeText(temporaryPath, defaultConfigText())

        val backupPath = if (existing != null) "$path.bak-${time(null)}" else null
        if (backupPath != null && rename(path, backupPath) != 0) {
            remove(temporaryPath)
            throw CliException("Unable to back up existing configuration: $path")
        }
        if (rename(temporaryPath, path) != 0) {
            backupPath?.let { rename(it, path) }
            remove(temporaryPath)
            throw CliException("Unable to install configuration atomically: $path")
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
                    ?: throw CliException("Unable to expand Skill path '$path': home directory is unavailable.")
            return home.trimEnd('/', '\\') + path.drop(1)
        }
        if (path.startsWith('~')) {
            throw CliException("Unsupported Skill path '$path': only '~/' and '~\\' home expansion are supported.")
        }
        return path
    }

    private fun readText(path: String): String? {
        val file = fopen(path, "rb") ?: return null
        val text = StringBuilder()
        try {
            memScoped {
                val buffer = allocArray<ByteVar>(CONFIG_BUFFER_SIZE)
                while (fgets(buffer, CONFIG_BUFFER_SIZE, file) != null) {
                    text.append(buffer.toKString())
                }
            }
        } finally {
            fclose(file)
        }
        return text.toString()
    }

    private fun writeText(
        path: String,
        text: String,
    ) {
        val file =
            fopen(path, "wb")
                ?: throw CliException("Unable to create config file: $path")

        try {
            if (fputs(text, file) < 0) {
                throw CliException("Unable to write config file: $path")
            }
        } finally {
            fclose(file)
        }
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

private const val CONFIG_BUFFER_SIZE = 8192

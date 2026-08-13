package org.koaks.agent.config

import org.koaks.agent.platform.ConfigFileSystem
import org.koaks.agent.platform.Environment
import org.koaks.agent.platform.PlatformFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigFileLoaderTest {
    @Test
    fun startupDoesNotCreateMissingConfig() {
        val home = ".koaks-config-loader-test-${Random.nextInt(0, Int.MAX_VALUE)}"
        try {
            ConfigFileSystem.createDirectory(home)
            val error = assertFailsWith<ConfigException> { ConfigFileLoader.load(TestEnvironment("HOME" to home)) }
            assertEquals(ConfigFailure.FileNotFound("$home/.koaks/config.toml"), error.failure)
        } finally {
            PlatformFileSystem.removePath(home)
        }
    }

    @Test
    fun initCreatesVersionedConfigExplicitly() {
        val home = ".koaks-config-init-test-${Random.nextInt(0, Int.MAX_VALUE)}"
        val directory = "$home/.koaks"
        val path = "$directory/config.toml"
        try {
            ConfigFileSystem.createDirectory(home)
            ConfigFileLoader.initialize(TestEnvironment("HOME" to home), force = false)
            val config = ConfigFileLoader.load(TestEnvironment("HOME" to home))
            assertEquals(1, config.schemaVersion)
        } finally {
            PlatformFileSystem.removePath(path)
            PlatformFileSystem.removePath(directory)
            PlatformFileSystem.removePath(home)
        }
    }
}

private class TestEnvironment(
    private val entries: Map<String, String>,
) : Environment {
    constructor(vararg pairs: Pair<String, String>) : this(mapOf(*pairs))

    override fun get(key: String): String? = entries[key]
}

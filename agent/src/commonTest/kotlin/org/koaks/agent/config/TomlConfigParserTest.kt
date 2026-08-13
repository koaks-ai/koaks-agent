package org.koaks.agent.config

import org.koaks.agent.provider.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TomlConfigParserTest {
    @Test
    fun parsesVersionedPlaintextCredentials() {
        val config =
            TomlConfigParser.parse(
                """
                schema_version = 1
                provider = "openai"

                [providers.openai]
                api_key = "secret"
                model = "gpt-test"
                """.trimIndent(),
            )

        assertEquals(1, config.schemaVersion)
        assertEquals(
            "secret",
            config.providers
                .getValue(Provider.OPENAI)
                .apiKey
                ?.value,
        )
    }

    @Test
    fun parsesPlaintextCredentials() {
        val config =
            TomlConfigParser.parse(
                """
                schema_version = 1
                [providers.openai]
                api_key = "secret"
                """.trimIndent(),
            )

        val apiKey = config.providers.getValue(Provider.OPENAI).apiKey
        assertEquals("secret", apiKey?.value)
        assertEquals("<redacted>", apiKey.toString())
    }

    @Test
    fun parsesMultipleProviderTables() {
        val config =
            TomlConfigParser.parse(
                """
                schema_version = 1
                provider = "openai"

                [providers.openai]
                base_url = "https://api.example.test/v1"
                model = "model-a"

                [providers.qwen]
                base_url = "https://qwen.example.test/compatible-mode"
                model = "model-b"
                """.trimIndent(),
            )

        assertEquals("https://api.example.test/v1", config.providers.getValue(Provider.OPENAI).baseUrl)
        assertEquals("https://qwen.example.test/compatible-mode", config.providers.getValue(Provider.QWEN).baseUrl)
        assertEquals(listOf(Provider.OPENAI, Provider.QWEN), config.providerOrder)
    }

    @Test
    fun rejectsRemovedCredentialReferences() {
        val error =
            assertFailsWith<ConfigException> {
                TomlConfigParser.parse(
                    """
                    schema_version = 1
                    [providers.openai]
                    credential_source = "environment"
                    credential_name = "OPENAI_API_KEY"
                    """.trimIndent(),
                )
            }

        assertEquals(
            "'credential_source' has been removed. Configure the provider with api_key instead.",
            error.failure.let { (it as ConfigFailure.Parse).detail },
        )
    }
}

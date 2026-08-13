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

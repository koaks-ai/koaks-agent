package org.koaks.agent.config

import org.koaks.agent.credential.CredentialRef
import org.koaks.agent.credential.CredentialSource
import org.koaks.agent.provider.Provider
import kotlin.test.Test
import kotlin.test.assertEquals

class TomlConfigParserTest {
    @Test
    fun parsesVersionedCredentialReferences() {
        val config =
            TomlConfigParser.parse(
                """
                schema_version = 1
                provider = "openai"

                [providers.openai]
                credential_source = "environment"
                credential_name = "OPENAI_API_KEY"
                model = "gpt-test"
                """.trimIndent(),
            )

        assertEquals(1, config.schemaVersion)
        assertEquals(
            CredentialRef(CredentialSource.ENVIRONMENT, "OPENAI_API_KEY"),
            config.providers.getValue(Provider.OPENAI).credentialRef,
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
}

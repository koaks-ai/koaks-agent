package org.koaks.agent.credential

import org.koaks.agent.config.CredentialRef
import org.koaks.agent.config.CredentialSource
import org.koaks.agent.platform.Environment
import org.koaks.agent.platform.SystemCredentialStore
import org.koaks.agent.platform.value

public fun interface CredentialResolver {
    public fun resolve(reference: CredentialRef): String?
}

public fun interface ToolApproval {
    public suspend fun approve(
        toolName: String,
        arguments: String,
    ): Boolean
}

public class PlatformCredentialResolver public constructor(
    private val environment: Environment,
) : CredentialResolver {
    override fun resolve(reference: CredentialRef): String? =
        when (reference.source) {
            CredentialSource.ENVIRONMENT -> environment.value(reference.name)
            CredentialSource.SYSTEM -> SystemCredentialStore.read(reference.name)
        }
}

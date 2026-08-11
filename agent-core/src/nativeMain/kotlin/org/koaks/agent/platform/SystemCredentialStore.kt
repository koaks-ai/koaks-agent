package org.koaks.agent.platform

internal expect object SystemCredentialStore {
    public fun read(name: String): String?
}

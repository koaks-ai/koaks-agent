package org.koaks.agent.platform

internal expect object ConfigFileSystem {
    public fun createDirectory(path: String): Boolean
}

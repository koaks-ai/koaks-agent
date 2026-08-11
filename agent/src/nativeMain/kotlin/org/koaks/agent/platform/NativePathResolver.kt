package org.koaks.agent.platform

internal expect object NativePathResolver {
    fun canonicalPath(path: String): String?
}

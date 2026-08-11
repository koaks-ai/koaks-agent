package org.koaks.agent.platform

import platform.posix.mkdir

internal actual object ConfigFileSystem {
    public actual fun createDirectory(path: String): Boolean = mkdir(path) == 0
}

@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.koaks.agent.platform

import platform.posix.mkdir

internal actual object ConfigFileSystem {
    public actual fun createDirectory(path: String): Boolean = mkdir(path) == 0
}

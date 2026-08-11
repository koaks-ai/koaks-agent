@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.agent.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.realpath

internal actual object NativePathResolver {
    actual fun canonicalPath(path: String): String? =
        memScoped {
            val buffer = allocArray<ByteVar>(32_768)
            realpath(path, buffer)?.toKString()
        }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.agent.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

internal actual object SystemCredentialStore {
    public actual fun read(name: String): String? {
        val command = "security find-generic-password -s org.koaks.agent -a ${shellQuote(name)} -w 2>/dev/null"
        val pipe = popen(command, "r") ?: return null
        return try {
            memScoped {
                val buffer = allocArray<ByteVar>(8192)
                val line = fgets(buffer, 8192, pipe)?.toKString()?.trim()
                line?.takeIf { it.isNotEmpty() }
            }
        } finally {
            pclose(pipe)
        }
    }
}

private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

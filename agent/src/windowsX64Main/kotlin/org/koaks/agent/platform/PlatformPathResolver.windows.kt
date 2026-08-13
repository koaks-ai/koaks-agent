@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.koaks.agent.platform

import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.FILE_FLAG_BACKUP_SEMANTICS
import platform.windows.FILE_SHARE_DELETE
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.GetFinalPathNameByHandleW
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.OPEN_EXISTING

internal actual object PlatformPathResolver {
    actual fun canonicalPath(path: String): String? =
        memScoped {
            val handle =
                CreateFileW(
                    lpFileName = path,
                    dwDesiredAccess = 0u,
                    dwShareMode = (FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE).toUInt(),
                    lpSecurityAttributes = null,
                    dwCreationDisposition = OPEN_EXISTING.toUInt(),
                    dwFlagsAndAttributes = FILE_FLAG_BACKUP_SEMANTICS.toUInt(),
                    hTemplateFile = null,
                )
            if (handle == INVALID_HANDLE_VALUE) return@memScoped null

            val buffer = allocArray<UShortVar>(32_768)
            try {
                val length = GetFinalPathNameByHandleW(handle, buffer, 32_768u, 0u)
                if (length == 0u || length >= 32_768u) null else buffer.toKString().removeExtendedPathPrefix()
            } finally {
                CloseHandle(handle)
            }
        }
}

private fun String.removeExtendedPathPrefix(): String =
    when {
        startsWith("\\\\?\\UNC\\", ignoreCase = true) -> "\\\\" + drop(8)
        startsWith("\\\\?\\") -> drop(4)
        else -> this
    }

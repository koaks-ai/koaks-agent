package org.koaks.agent.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformJvmTest {
    @Test
    fun readsUtf8WindowsAndPreservesNewlineCharacterCount() {
        val path = ".koaks-jvm-window-${System.nanoTime()}.txt"
        val content = "alpha\r\nbeta\n最后"
        try {
            assertEquals(null, PlatformFileSystem.writeWholeFile(path, content).error)

            val scan = PlatformFileSystem.readTextWindow(path, offset = 2, limit = 1, maxCapturedChars = 100)

            assertEquals(3, scan.totalLines)
            assertEquals(content.length.toLong(), scan.totalChars)
            assertEquals(listOf(NumberedTextLine(2, "beta")), scan.lines)
            assertTrue(!scan.truncatedByChars)
        } finally {
            PlatformFileSystem.removePath(path)
        }
    }

    @Test
    fun shellPreservesExitCodeAndBoundsOutput() {
        val result =
            PlatformProcess.runShell(
                command =
                    if (PlatformInfo.operatingSystemName == "Windows") {
                        "Write-Output ('abcdefghijklmnopqrstuvwxyz')"
                    } else {
                        "printf 'abcdefghijklmnopqrstuvwxyz'"
                    },
                maxOutputChars = 7,
            )

        assertEquals(0, result.status)
        assertEquals("abcdefg", result.output)
        assertTrue(result.truncated)
        assertTrue(result.totalOutputChars >= 26)
    }

    @Test
    fun shellTimeoutUsesStableExitCode() {
        val command = if (PlatformInfo.operatingSystemName == "Windows") "Start-Sleep -Seconds 10" else "sleep 10"
        val result = PlatformProcess.runShell(command, maxOutputChars = 100, timeoutMillis = 100)

        assertEquals(124, result.status)
    }
}

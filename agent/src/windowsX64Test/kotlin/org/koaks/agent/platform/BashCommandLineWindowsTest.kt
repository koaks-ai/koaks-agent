package org.koaks.agent.platform

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class BashCommandLineWindowsTest {
    @Test
    fun runsPowerShellSyntax() {
        val result =
            NativeProcess.runShell(
                command = "\$items = @(1, 2, 3); Write-Output \$items.Count",
                maxOutputChars = 1_000,
            )

        assertEquals(0, result.status)
        assertContains(result.output, "3")
    }

    @Test
    fun returnsNativeProcessExitCode() {
        val result =
            NativeProcess.runShell(
                command = "cmd.exe /c exit 7",
                maxOutputChars = 1_000,
            )

        assertEquals(7, result.status)
    }

    @Test
    fun terminatesCommandsThatExceedTheDeadline() {
        val started = TimeSource.Monotonic.markNow()
        val result =
            NativeProcess.runShell(
                command = "Start-Sleep -Seconds 10",
                maxOutputChars = 1_000,
                timeoutMillis = 100,
            )

        assertEquals(124, result.status)
        assertTrue(started.elapsedNow().inWholeSeconds < 5)
    }

    @Test
    fun rendersPowerShellErrorsAsPlainTextInsteadOfCliXml() {
        val result =
            NativeProcess.runShell(
                command = "Get-ChildItem -DefinitelyNotARealParameter",
                maxOutputChars = 10_000,
            )

        assertEquals(1, result.status)
        assertContains(result.output, "Get-ChildItem")
        assertFalse(result.output.contains("#< CLIXML"))
        assertFalse(result.output.contains("<Objs Version="))
    }

    @Test
    fun drainsLargeStdoutAndStderrWithoutDeadlocking() {
        val result =
            NativeProcess.runShell(
                command =
                    "1..1000 | ForEach-Object { " +
                        "[Console]::Out.WriteLine(('output-{0:D4}-' -f \$_) + ('x' * 80)); " +
                        "[Console]::Error.WriteLine(('noise-{0:D4}-' -f \$_) + ('y' * 80)) }",
                maxOutputChars = 1_000,
            )

        assertEquals(0, result.status)
        assertContains(result.output, "output-0001-")
        assertFalse(result.output.contains("noise-"))
        assertEquals(1_000, result.output.length)
        assertFalse(result.output.contains("KOAKS-RESULT/1"))
        assertTrue(result.truncated)
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.agent.tool

import kotlinx.cinterop.toKString
import org.koaks.agent.platform.NativeFileSystem
import org.koaks.agent.platform.NativeProcess
import org.koaks.agent.tool.policy.WorkspaceAccessPolicy
import org.koaks.framework.model.AgentFrameworkException
import platform.posix.getenv
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkspaceJunctionPolicyTest {
    @Test
    fun rejectsJunctionsThatResolveOutsideTheWorkspace() {
        val temp = getenv("TEMP")?.toKString() ?: NativeFileSystem.workingDirectory()
        val base = "${temp.trimEnd('/', '\\')}/koaks-policy-test-${Random.nextLong().toULong()}"
        val workspace = "$base/workspace"
        val outside = "$base/outside"
        val junction = "$workspace/link"
        val setup =
            NativeProcess.runShell(
                command =
                    """
                    New-Item -ItemType Directory -Force -Path '${workspace.psQuote()}', '${outside.psQuote()}' | Out-Null
                    Set-Content -LiteralPath '${(outside + "/secret.txt").psQuote()}' -Value 'secret'
                    New-Item -ItemType Junction -Path '${junction.psQuote()}' -Target '${outside.psQuote()}' | Out-Null
                    """.trimIndent(),
                maxOutputChars = 2_000,
            )
        assertEquals(0, setup.status, setup.output)

        try {
            val policy = WorkspaceAccessPolicy(workspace)
            assertFailsWith<AgentFrameworkException> { policy.resolveRead("link/secret.txt") }
        } finally {
            val cleanup =
                NativeProcess.runShell(
                    command =
                        """
                        if ([System.IO.Directory]::Exists('${junction.psQuote()}')) {
                            [System.IO.Directory]::Delete('${junction.psQuote()}')
                        }
                        Remove-Item -LiteralPath '${base.psQuote()}' -Recurse -Force -Confirm:${'$'}false -ErrorAction SilentlyContinue
                        if (Test-Path -LiteralPath '${base.psQuote()}') { exit 1 }
                        """.trimIndent(),
                    maxOutputChars = 2_000,
                )
            assertEquals(0, cleanup.status, cleanup.output)
        }
    }
}

private fun String.psQuote(): String = replace("'", "''")

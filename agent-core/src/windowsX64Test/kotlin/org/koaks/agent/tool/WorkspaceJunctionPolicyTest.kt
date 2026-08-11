package org.koaks.agent.tool

import org.koaks.agent.platform.NativeCliIo
import org.koaks.framework.model.AgentFrameworkException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkspaceJunctionPolicyTest {
    @Test
    fun rejectsJunctionsThatResolveOutsideTheWorkspace() {
        val base = "${NativeCliIo.workingDirectory()}/.koaks-policy-test-${Random.nextLong().toULong()}"
        val workspace = "$base/workspace"
        val outside = "$base/outside"
        val junction = "$workspace/link"
        val setup =
            NativeCliIo.runBash(
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
            NativeCliIo.runBash(
                command =
                    """
                    Remove-Item -LiteralPath '${junction.psQuote()}' -Force -ErrorAction SilentlyContinue
                    Remove-Item -LiteralPath '${base.psQuote()}' -Recurse -Force -ErrorAction SilentlyContinue
                    """.trimIndent(),
                maxOutputChars = 2_000,
            )
        }
    }
}

private fun String.psQuote(): String = replace("'", "''")

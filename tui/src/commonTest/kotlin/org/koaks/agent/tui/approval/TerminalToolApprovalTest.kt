package org.koaks.agent.tui.approval

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TerminalToolApprovalTest {
    @Test
    fun sessionAllowanceSkipsLaterRequestsForTheSameToolOnly() =
        runBlocking {
            val approval = TerminalToolApproval()

            val first = async { approval.request("Bash", "{\"command\":\"echo first\"}") }
            approval.requests.receive().respond(ApprovalDecision.AllowForSession)
            assertTrue(first.await())

            assertTrue(
                withTimeout(1.seconds) {
                    approval.request("Bash", "{\"command\":\"echo second\"}")
                },
            )
            assertTrue(approval.requests.tryReceive().isFailure)

            val otherTool = async { approval.request("Write", "{\"path\":\"result.txt\"}") }
            approval.requests.receive().respond(ApprovalDecision.Deny)
            assertFalse(otherTool.await())
        }

    @Test
    fun allowOncePromptsAgainForTheSameTool() =
        runBlocking {
            val approval = TerminalToolApproval()

            val first = async { approval.request("Bash", "{}") }
            approval.requests.receive().respond(ApprovalDecision.AllowOnce)
            assertTrue(first.await())

            val second = async { approval.request("Bash", "{}") }
            approval.requests.receive().respond(ApprovalDecision.Deny)
            assertFalse(second.await())
        }
}

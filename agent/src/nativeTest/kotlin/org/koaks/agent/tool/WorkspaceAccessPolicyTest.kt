package org.koaks.agent.tool

import org.koaks.agent.platform.NativeFileSystem
import org.koaks.agent.tool.policy.WorkspaceAccessPolicy
import org.koaks.framework.model.AgentFrameworkException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspaceAccessPolicyTest {
    @Test
    fun rejectsTraversalOutsideWorkspace() {
        val root = NativeFileSystem.workingDirectory()
        val policy = WorkspaceAccessPolicy(root)

        val failure =
            assertFailsWith<AgentFrameworkException> {
                policy.resolveWrite("../outside-${Random.nextInt()}.txt")
            }

        assertTrue(failure.error.message.contains("workspace", ignoreCase = true))
    }
}

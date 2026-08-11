package org.koaks.agent.tool

import kotlinx.coroutines.runBlocking
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.AgentFrameworkException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BuiltinCliToolsFailureTest {
    @Test
    fun wrapsNonExceptionThrowableWithCrashDetails() =
        runBlocking {
            val original = AssertionError("native write failure")

            val thrown =
                assertFailsWith<AgentFrameworkException> {
                    executeToolSafely("Write") { throw original }
                }
            val error = thrown.error as AgentError.ToolError

            assertEquals("Write", error.toolName)
            assertEquals(original, error.cause)
            assertContains(error.message, "tool 'Write' crashed")
            assertContains(error.message, "AssertionError")
            assertContains(error.message, "native write failure")
        }
}

package org.koaks.agent.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.koaks.framework.loop.Agent
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.loop.agent
import org.koaks.framework.loop.tool
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.runtime.AgentRuntime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubagentToolTest {
    @Serializable
    private data object NoArgs

    @Test
    fun appendsReadOnlyFileGuardToChildPrompt() = runBlocking {
        val model = CapturingModel()
        val child = childAgent("koaks-agent-subagent", model)
        val parent = parentAgent(
            id = "task-read-only-guard-parent",
            subagent = SubagentTool(child),
            calls = listOf(subagentCall("subagent-1", "inspect files", "Inspect the repository")),
        )

        AgentRuntime().use { runtime ->
            runtime.stream(parent, "go").toList()
        }

        val prompt = model.prompt ?: error("Sub-agent prompt was not captured")
        assertTrue(prompt.startsWith("Inspect the repository\n\n"))
        assertContains(
            prompt,
            "Unless the task above explicitly asks you to modify or edit files",
        )
        assertContains(prompt, "may only inspect or read them")
    }

    @Test
    fun failedChildUsesTheExplicitToolErrorChannel() = runBlocking {
        val child = childAgent(
            id = "koaks-agent-subagent",
            model = StaticModel(listOf(ModelEvent.Failed(AgentError.ModelError("child boom", false)))),
        )
        val parent = parentAgent(
            id = "task-failure-parent",
            subagent = SubagentTool(child),
            calls = listOf(subagentCall("subagent-1", "same description", "fail")),
        )

        AgentRuntime().use { runtime ->
            val events = runtime.stream(parent, "go").toList()
            val result = events.filterIsInstance<AgentEvent.ToolResult>().single()

            assertTrue(result.isError)
            assertEquals("child boom", result.output)
            assertTrue(events.any { it is AgentEvent.Completed })
        }
    }

    @Test
    fun invalidSubagentInputUsesTheExplicitToolErrorChannel() = runBlocking {
        val child = childAgent("koaks-agent-subagent", StaticModel(successEvents("unused")))
        val parent = parentAgent(
            id = "task-validation-parent",
            subagent = SubagentTool(child),
            calls = listOf(subagentCall("subagent-1", "", "prompt")),
        )

        AgentRuntime().use { runtime ->
            val result = runtime.stream(parent, "go").toList()
                .filterIsInstance<AgentEvent.ToolResult>()
                .single()

            assertTrue(result.isError)
            assertContains(result.output, "description is required")
        }
    }

    @Test
    fun terminatedChildUsesTheExplicitToolErrorChannelWithPartialOutput() = runBlocking {
        val child = agent {
            id = "koaks-agent-subagent"
            model {
                custom(
                    StaticModel(
                        listOf(
                            ModelEvent.TextDelta("partial"),
                            ModelEvent.ToolCallCompleted(ToolCall("noop-1", "noop", "{}")),
                            ModelEvent.Completed(Usage.ZERO),
                        ),
                    ),
                )
            }
            tools { tool<NoArgs>("noop", "no-op") { "ok" } }
            terminateAfter(maxSteps = 1)
        }
        val parent = parentAgent(
            id = "task-terminated-parent",
            subagent = SubagentTool(child),
            calls = listOf(subagentCall("subagent-1", "terminated subagent", "go")),
        )

        AgentRuntime().use { runtime ->
            val result = runtime.stream(parent, "go").toList()
                .filterIsInstance<AgentEvent.ToolResult>()
                .single()

            assertTrue(result.isError)
            assertContains(result.output, "Subagent terminated")
            assertContains(result.output, "partial")
        }
    }

    @Test
    fun identicalDescriptionsReuseOneAgentDefinitionWithoutIdConflict() = runBlocking {
        val child = childAgent("koaks-agent-subagent", StaticModel(successEvents("done")))
        val parent = parentAgent(
            id = "task-same-description-parent",
            subagent = SubagentTool(child),
            calls = listOf(
                subagentCall("subagent-1", "same description", "slice one"),
                subagentCall("subagent-2", "same description", "slice two"),
            ),
        )

        AgentRuntime { maxConcurrency = 3 }.use { runtime ->
            val results = runtime.stream(parent, "go").toList()
                .filterIsInstance<AgentEvent.ToolResult>()

            assertEquals(2, results.size)
            assertTrue(results.all { !it.isError })
        }
    }

    @Test
    fun fiveSubagentsRunInParallelAndOneFailureDoesNotCancelTheOthers() = runBlocking {
        val model = ParallelSubagentModel(expectedSubagents = 5)
        val child = childAgent("koaks-agent-subagent", model)
        val parent = parentAgent(
            id = "task-five-subagents-parent",
            subagent = SubagentTool(child),
            calls = (0 until 5).map { index ->
                subagentCall(
                    id = "subagent-$index",
                    description = "batch",
                    prompt = if (index == 2) "fail-$index" else "ok-$index",
                )
            },
        )

        AgentRuntime { maxConcurrency = 8 }.use { runtime ->
            val events = runtime.stream(parent, "go").toList()
            val results = events.filterIsInstance<AgentEvent.ToolResult>()

            assertEquals(5, model.started)
            assertEquals(5, results.size)
            assertEquals(1, results.count { it.isError })
            assertEquals(4, results.count { !it.isError })
            assertTrue(events.any { it is AgentEvent.Completed })
        }
    }

    private fun parentAgent(id: String, subagent: SubagentTool, calls: List<ToolCall>): Agent {
        val toolStep = buildList<ModelEvent> {
            calls.forEach { add(ModelEvent.ToolCallCompleted(it)) }
            add(ModelEvent.Completed(Usage.ZERO))
        }
        return agent {
            this.id = id
            model {
                custom(
                    ScriptedModel(
                        listOf(
                            toolStep,
                            listOf(ModelEvent.TextDelta("parent complete"), ModelEvent.Completed(Usage.ZERO)),
                        ),
                    ),
                )
            }
            tools { tool(subagent) }
            terminateAfter(maxSteps = 5)
        }
    }

    private fun childAgent(id: String, model: LanguageModel): Agent = agent {
        this.id = id
        model { custom(model) }
        terminateAfter(maxSteps = 5)
    }

    private fun subagentCall(id: String, description: String, prompt: String): ToolCall =
        ToolCall(
            id = id,
            name = "Subagent",
            arguments = """{"description":"$description","prompt":"$prompt"}""",
        )

    private fun successEvents(text: String): List<ModelEvent> =
        listOf(ModelEvent.TextDelta(text), ModelEvent.Completed(Usage.ZERO))

    private class StaticModel(
        private val events: List<ModelEvent>,
    ) : LanguageModel {
        override val capabilities: ModelCapabilities = ModelCapabilities()

        override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
            events.forEach { emit(it) }
        }
    }

    private class CapturingModel : LanguageModel {
        override val capabilities: ModelCapabilities = ModelCapabilities()
        var prompt: String? = null
            private set

        override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
            prompt = request.messages.last().text
            emit(ModelEvent.TextDelta("done"))
            emit(ModelEvent.Completed(Usage.ZERO))
        }
    }

    private class ScriptedModel(
        scripts: List<List<ModelEvent>>,
    ) : LanguageModel {
        override val capabilities: ModelCapabilities = ModelCapabilities()
        private val remaining = ArrayDeque(scripts)

        override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
            val events = if (remaining.isEmpty()) emptyList() else remaining.removeFirst()
            events.forEach { emit(it) }
        }
    }

    private class ParallelSubagentModel(
        private val expectedSubagents: Int,
    ) : LanguageModel {
        override val capabilities: ModelCapabilities = ModelCapabilities()
        private val mutex = Mutex()
        private val allStarted = CompletableDeferred<Unit>()
        var started: Int = 0
            private set

        override fun generate(request: ChatRequest): Flow<ModelEvent> = flow {
            mutex.withLock {
                started++
                if (started == expectedSubagents) allStarted.complete(Unit)
            }
            allStarted.await()
            val prompt = request.messages.last().text
            if (prompt.startsWith("fail-")) {
                emit(ModelEvent.Failed(AgentError.ModelError("subagent failed: $prompt", false)))
            } else {
                emit(ModelEvent.TextDelta("completed: $prompt"))
                emit(ModelEvent.Completed(Usage.ZERO))
            }
        }
    }
}

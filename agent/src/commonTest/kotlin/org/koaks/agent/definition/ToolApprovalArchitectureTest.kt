package org.koaks.agent.definition

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.koaks.agent.credential.ApiKey
import org.koaks.agent.platform.BashCommandLine
import org.koaks.agent.platform.PlatformFileSystem
import org.koaks.agent.provider.ModelRuntimeSettings
import org.koaks.agent.provider.Provider
import org.koaks.agent.provider.ProviderBinding
import org.koaks.agent.provider.ProviderRegistry
import org.koaks.agent.provider.ProviderSettings
import org.koaks.agent.tool.BuiltinToolSet
import org.koaks.agent.tool.approval.ToolApprovalPort
import org.koaks.agent.tool.delegate.SubagentFactory
import org.koaks.agent.tool.sideEffectToolNames
import org.koaks.framework.loop.AgentState
import org.koaks.framework.loop.ModelScope
import org.koaks.framework.loop.ModelSelection
import org.koaks.framework.memory.WindowMemoryProvider
import org.koaks.framework.middleware.HumanApproval
import org.koaks.framework.middleware.ToolContext
import org.koaks.framework.middleware.ToolDecision
import org.koaks.framework.model.ChatRequest
import org.koaks.framework.model.LanguageModel
import org.koaks.framework.model.ModelCapabilities
import org.koaks.framework.model.ModelEvent
import org.koaks.framework.model.ToolCall
import org.koaks.framework.model.Usage
import org.koaks.runtime.AgentRuntime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolApprovalArchitectureTest {
    @Test
    fun registrationAndApprovalNamesAreDerivedFromActualTools() {
        val toolSet = BuiltinToolSet()
        val subagentTools = toolSet.subagentTools()
        val mainTools = toolSet.mainTools(SubagentFactory { error("not executed") })

        assertEquals(
            mainTools.filter { it.hasSideEffects }.mapTo(linkedSetOf()) { it.name },
            mainTools.sideEffectToolNames(),
        )
        assertEquals(
            subagentTools.filter { it.hasSideEffects }.mapTo(linkedSetOf()) { it.name },
            subagentTools.sideEffectToolNames(),
        )
        assertEquals(setOf(BashCommandLine.toolName), subagentTools.sideEffectToolNames())
    }

    @Test
    fun mainAgentGuardsEveryRegisteredSideEffectTool() =
        runBlocking {
            val requests = mutableListOf<String>()
            val factory =
                testFactory(TestLanguageModel()) { name, _ ->
                    requests += name
                    false
                }
            val agent = factory.build(testSettings(), WindowMemoryProvider(8))
            try {
                val tools = BuiltinToolSet().mainTools(SubagentFactory { error("not executed") })
                val sideEffectNames = tools.sideEffectToolNames()
                val approval = agent.hooks.filterIsInstance<HumanApproval>().single()

                sideEffectNames.forEach { name ->
                    val decision = approval.onToolCall(ToolContext(ToolCall("call-$name", name, "{}"), AgentState(emptyList())))
                    assertIs<ToolDecision.Deny>(decision)
                }

                assertEquals(sideEffectNames, requests.toSet())
                assertTrue(sideEffectNames.all { it in agent.tools.names() })
            } finally {
                agent.close()
            }
        }

    @Test
    fun deniedSubagentShellNeverExecutes() =
        runBlocking {
            val markerName = ".koaks-approval-denied-${Random.nextLong().toULong()}"
            val markerPath = "${PlatformFileSystem.workingDirectory()}/$markerName"
            val command =
                if (BashCommandLine.toolName == "PowerShell") {
                    "Set-Content -LiteralPath '$markerName' -Value 'ran'"
                } else {
                    "printf ran > '$markerName'"
                }
            val model =
                TestLanguageModel(
                    listOf(
                        listOf(
                            ModelEvent.ToolCallCompleted(
                                ToolCall("shell-call", BashCommandLine.toolName, "{\"command\":\"$command\"}"),
                            ),
                            ModelEvent.Completed(Usage.ZERO),
                        ),
                        listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)),
                    ),
                )
            val approvals = mutableListOf<String>()
            val factory =
                testFactory(model) { name, _ ->
                    approvals += name
                    false
                }
            val subagent = factory.buildSubagent(testSettings())
            try {
                AgentRuntime().use { runtime -> runtime.run(subagent, "run denied command") }

                assertEquals(listOf(BashCommandLine.toolName), approvals)
                assertFalse(PlatformFileSystem.fileExists(markerPath))
                assertTrue(subagent.hooks.single() is HumanApproval)
            } finally {
                PlatformFileSystem.removePath(markerPath)
                subagent.close()
            }
        }

    @Test
    fun approvedShellExecutesForMainAndSubagentAgents() =
        runBlocking {
            suspend fun verify(buildAgent: (AgentDefinitionFactory) -> org.koaks.framework.loop.Agent) {
                val markerName = ".koaks-approval-allowed-${Random.nextLong().toULong()}"
                val markerPath = "${PlatformFileSystem.workingDirectory()}/$markerName"
                val command =
                    if (BashCommandLine.toolName == "PowerShell") {
                        "Set-Content -LiteralPath '$markerName' -Value 'ran'"
                    } else {
                        "printf ran > '$markerName'"
                    }
                val model =
                    TestLanguageModel(
                        listOf(
                            listOf(
                                ModelEvent.ToolCallCompleted(
                                    ToolCall("shell-call", BashCommandLine.toolName, "{\"command\":\"$command\"}"),
                                ),
                                ModelEvent.Completed(Usage.ZERO),
                            ),
                            listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO)),
                        ),
                    )
                val approvals = mutableListOf<String>()
                val agent =
                    buildAgent(
                        testFactory(model) { name, _ ->
                            approvals += name
                            true
                        },
                    )
                try {
                    AgentRuntime().use { runtime -> runtime.run(agent, "run approved command") }

                    assertEquals(listOf(BashCommandLine.toolName), approvals)
                    assertTrue(PlatformFileSystem.fileExists(markerPath))
                } finally {
                    PlatformFileSystem.removePath(markerPath)
                    agent.close()
                }
            }

            verify { factory -> factory.build(testSettings(), WindowMemoryProvider(8)) }
            verify { factory -> factory.buildSubagent(testSettings()) }
        }
}

private object TestProviderSettings : ProviderSettings

private class TestProviderBinding(
    private val model: LanguageModel,
) : ProviderBinding<TestProviderSettings> {
    override val provider: Provider = Provider.OPENAI

    override fun settings(model: ModelRuntimeSettings): TestProviderSettings = TestProviderSettings

    override fun ModelScope.select(
        settings: TestProviderSettings,
        credential: String,
    ): ModelSelection = custom(model)
}

private class TestLanguageModel(
    scripts: List<List<ModelEvent>> = listOf(listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed(Usage.ZERO))),
) : LanguageModel {
    override val capabilities: ModelCapabilities = ModelCapabilities()
    private val remaining = ArrayDeque(scripts)

    override fun generate(request: ChatRequest): Flow<ModelEvent> =
        flow {
            val events = if (remaining.isEmpty()) emptyList() else remaining.removeFirst()
            events.forEach { emit(it) }
        }
}

private fun testFactory(
    model: LanguageModel,
    approve: suspend (String, String) -> Boolean,
): AgentDefinitionFactory =
    AgentDefinitionFactory(
        providers = ProviderRegistry.Builder().register(TestProviderBinding(model)).build(),
        toolApproval = ToolApprovalPort(approve),
    )

private fun testSettings(): EffectiveAgentSettings =
    EffectiveAgentSettings(
        model =
            ModelRuntimeSettings(
                provider = Provider.OPENAI,
                baseUrl = Provider.OPENAI.defaultBaseUrl,
                modelName = "test-model",
                temperature = null,
                reasoningEnabled = false,
            ),
        apiKey = ApiKey("test-key"),
        instructions = "test",
        skillPaths = emptyList(),
        skills = emptyList(),
    )

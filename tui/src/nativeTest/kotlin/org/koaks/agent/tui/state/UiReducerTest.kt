package org.koaks.agent.tui.state

import org.koaks.agent.credential.CredentialSource
import org.koaks.agent.provider.Provider
import org.koaks.agent.session.CredentialSummary
import org.koaks.agent.session.SessionSnapshot
import org.koaks.framework.memory.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiReducerTest {
    private val snapshot =
        SessionSnapshot(
            provider = Provider.QWEN,
            modelName = "test-model",
            baseUrl = Provider.QWEN.defaultBaseUrl,
            credential = CredentialSummary.Reference(CredentialSource.ENVIRONMENT, "QWEN_API_KEY"),
            threadId = ThreadId("test-thread"),
            historyMessages = 8,
            reasoningEnabled = false,
            skillPaths = emptyList(),
            skills = emptyList(),
            availableProviders = listOf(Provider.QWEN),
            availableModels = listOf("test-model"),
        )

    @Test
    fun runLifecycleIsReducedDeterministically() {
        val reducer = UiReducer()
        val started = reducer.reduce(UiState(snapshot), UiAction.InputSubmitted("hello"))
        assertTrue(started.state.running)
        assertEquals(listOf(UiEffect.RunAgent("hello")), started.effects)

        val completed = reducer.reduce(started.state, UiAction.RunCompleted)
        assertFalse(completed.state.running)
    }
}

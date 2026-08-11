package org.koaks.agent.app

import org.koaks.agent.config.ConfigResolver
import org.koaks.agent.config.FileConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiReducerTest {
    private val config = ConfigResolver.resolve(FileConfig(schemaVersion = 1))

    @Test
    fun runLifecycleIsReducedDeterministically() {
        val reducer = UiReducer()
        val started = reducer.reduce(UiState(config), UiAction.InputSubmitted("hello"))
        assertTrue(started.state.running)
        assertEquals(listOf(UiEffect.RunAgent("hello")), started.effects)

        val completed = reducer.reduce(started.state, UiAction.RunCompleted)
        assertFalse(completed.state.running)
    }
}

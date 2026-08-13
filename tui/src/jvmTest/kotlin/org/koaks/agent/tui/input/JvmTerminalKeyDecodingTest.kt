package org.koaks.agent.tui.input

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmTerminalKeyDecodingTest {
    @Test
    fun decodesPlainAndModifiedArrowKeys() {
        assertEquals(TerminalKey.Up, decodeJvmCsiSequence("A"))
        assertEquals(TerminalKey.Down, decodeJvmCsiSequence("B"))
        assertEquals(TerminalKey.Right, decodeJvmCsiSequence("1;5C"))
        assertEquals(TerminalKey.Left, decodeJvmCsiSequence("1;2D"))
    }

    @Test
    fun decodesPagingAndEditingKeys() {
        assertEquals(TerminalKey.Delete, decodeJvmCsiSequence("3~"))
        assertEquals(TerminalKey.PageUp, decodeJvmCsiSequence("5~"))
        assertEquals(TerminalKey.PageDown, decodeJvmCsiSequence("6~"))
    }
}

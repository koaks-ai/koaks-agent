package org.koaks.cli.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineEditorTest {
    private val suggestions = listOf(
        LineSuggestion("/help", "Show help"),
        LineSuggestion("/status", "Show status"),
        LineSuggestion("/exit", "Quit"),
    )

    @Test
    fun slashShowsAllCommandsAndSelectsFirstPrefixMatch() {
        val editor = editor()

        editor.accept(TerminalKey.Text("/"))
        val slashSnapshot = editor.snapshot()
        assertEquals(suggestions, slashSnapshot.suggestions)
        assertEquals(0, slashSnapshot.selectedSuggestionIndex)

        editor.accept(TerminalKey.Text("ex"))
        val prefixSnapshot = editor.snapshot()
        assertEquals(listOf(LineSuggestion("/exit", "Quit")), prefixSnapshot.suggestions)
        assertEquals(0, prefixSnapshot.selectedSuggestionIndex)
        assertEquals("/exit", prefixSnapshot.suggestions[prefixSnapshot.selectedSuggestionIndex!!].value)
    }

    @Test
    fun filtersUnrelatedCommandsOutOfMenu() {
        val editor = editor()

        editor.accept(TerminalKey.Text("/zz"))

        assertEquals(emptyList(), editor.snapshot().suggestions)
        assertNull(editor.snapshot().selectedSuggestionIndex)
    }

    @Test
    fun ordersBetterCommandMatchesFirst() {
        val editor = editor(
            listOf(
                LineSuggestion("/exit", "Quit"),
                LineSuggestion("/itinerary", "Show itinerary"),
            )
        )

        editor.accept(TerminalKey.Text("/it"))
        val snapshot = editor.snapshot()

        assertEquals("/itinerary", snapshot.suggestions.first().value)
        assertEquals(0, snapshot.selectedSuggestionIndex)
    }

    @Test
    fun tabAcceptsSelectedPrefixMatch() {
        val editor = editor()
        editor.accept(TerminalKey.Text("/ex"))

        editor.accept(TerminalKey.Tab)

        assertEquals("/exit", editor.snapshot().text)
    }

    @Test
    fun enterSubmitsSelectedPrefixMatch() {
        val editor = editor()
        editor.accept(TerminalKey.Text("/ex"))

        val result = editor.accept(TerminalKey.Enter)

        assertEquals(LineEditResult.Submit("/exit"), result)
    }

    @Test
    fun shiftEnterInsertsLineBreakWithoutSubmitting() {
        val editor = editor()
        editor.accept(TerminalKey.Text("first"))

        assertEquals(LineEditResult.Continue, editor.accept(TerminalKey.LineBreak))
        editor.accept(TerminalKey.Text("second"))

        assertEquals("first\nsecond", editor.snapshot().text)
        assertEquals(LineEditResult.Submit("first\nsecond"), editor.accept(TerminalKey.Enter))
    }

    @Test
    fun decodesCommonShiftEnterTerminalSequences() {
        assertEquals(TerminalKey.LineBreak, decodeCsiEnterKey("13;2u"))
        assertEquals(TerminalKey.LineBreak, decodeCsiEnterKey("27;2;13~"))
        assertEquals(TerminalKey.Enter, decodeCsiEnterKey("13u"))
        assertNull(decodeCsiEnterKey("3~"))
    }

    @Test
    fun onlyCompleteBuiltinCommandTokenIsRecognized() {
        val editor = editor()
        editor.accept(TerminalKey.Text("/ex"))
        assertNull(editor.snapshot().recognizedCommandEnd)

        editor.accept(TerminalKey.Text("it argument"))
        assertEquals(5, editor.snapshot().recognizedCommandEnd)
    }

    @Test
    fun arrowAndPageKeysProduceViewportScrollResultsOutsideCommandMenu() {
        val editor = editor(scrollPageRows = 7)

        assertEquals(LineEditResult.Scroll(1), editor.accept(TerminalKey.Up))
        assertEquals(LineEditResult.Scroll(-1), editor.accept(TerminalKey.Down))
        assertEquals(LineEditResult.Scroll(7), editor.accept(TerminalKey.PageUp))
        assertEquals(LineEditResult.Scroll(-7), editor.accept(TerminalKey.PageDown))
    }

    @Test
    fun upAndDownMoveTheCursorBetweenWrappedInputRows() {
        val editor = editor(inputWidth = 4)
        editor.accept(TerminalKey.Text("abcdefghij"))

        assertEquals(LineEditResult.Continue, editor.accept(TerminalKey.Up))
        assertEquals(6, editor.snapshot().cursor)

        assertEquals(LineEditResult.Continue, editor.accept(TerminalKey.Up))
        assertEquals(2, editor.snapshot().cursor)

        assertEquals(LineEditResult.Continue, editor.accept(TerminalKey.Down))
        assertEquals(6, editor.snapshot().cursor)
    }

    @Test
    fun multilinePasteStaysInEditorUntilEnter() {
        val editor = editor()

        assertEquals(LineEditResult.Continue, editor.accept(TerminalKey.Paste("a\r\nb\nc")))
        val snapshot = editor.snapshot()
        assertEquals("a\nb\nc", snapshot.text)
        assertEquals("[粘贴 3 行内容]", snapshot.displayText)
        assertEquals(snapshot.displayText.length, snapshot.displayCursor)
        assertEquals(3, snapshot.pastedLineCount)

        assertEquals(LineEditResult.Submit("a\nb\nc"), editor.accept(TerminalKey.Enter))
    }

    @Test
    fun singleLinePasteBehavesLikeTypedText() {
        val editor = editor()

        assertEquals(LineEditResult.Continue, editor.accept(TerminalKey.Paste("hello")))

        val snapshot = editor.snapshot()
        assertEquals("hello", snapshot.text)
        assertEquals("hello", snapshot.displayText)
        assertNull(snapshot.pastedLineCount)
    }

    @Test
    fun editingAfterMultilinePasteRevealsThePastedText() {
        val editor = editor()
        editor.accept(TerminalKey.Paste("a\nb"))

        editor.accept(TerminalKey.Text("!"))

        assertEquals("a\nb!", editor.snapshot().text)
        assertEquals("a\nb!", editor.snapshot().displayText)
        assertNull(editor.snapshot().pastedLineCount)
    }

    private fun editor(
        availableSuggestions: List<LineSuggestion> = suggestions,
        scrollPageRows: Int = 10,
        inputWidth: Int = Int.MAX_VALUE,
    ): LineEditor = LineEditor(
        LineReadRequest(
            suggestions = availableSuggestions,
            commandNames = availableSuggestions.mapTo(mutableSetOf()) { it.value },
            scrollPageRows = scrollPageRows,
            inputWidth = { inputWidth },
            onUpdate = {},
        )
    )
}

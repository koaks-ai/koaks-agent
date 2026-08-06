package org.koaks.cli.app

import org.koaks.cli.tui.Ansi
import org.koaks.cli.tui.LineEditorSnapshot
import org.koaks.cli.tui.LineSuggestion
import org.koaks.cli.tui.Output
import org.koaks.cli.tui.TerminalLayout
import org.koaks.cli.tui.Theme
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InputBoxTest {
    @Test
    fun staticInputUsesBlinkingBarCursorAndRestoresDefault() {
        val output = RecordingOutput()
        val theme = Theme(enabled = true)

        InputBox.renderStaticStart(output, theme)
        InputBox.renderStaticEnd(output, theme, inputWasEchoed = false)

        assertTrue(output.content.startsWith(Ansi.BLINKING_BAR_CURSOR))
        assertTrue(output.content.endsWith(Ansi.RESET_CURSOR_STYLE))
    }

    @Test
    fun rendersRecognizedCommandTokenInBlue() {
        val output = RecordingOutput()
        val snapshot = LineEditorSnapshot(
            text = "/exit now",
            cursor = 9,
            suggestions = emptyList(),
            selectedSuggestionIndex = null,
            recognizedCommandEnd = 5,
        )

        InputBox.renderStaticEditor(output, Theme(enabled = true), snapshot, previousMenuRows = 0)

        assertContains(
            output.content,
            "${Ansi.BLUE}/exit${Ansi.DEFAULT_FOREGROUND}${Ansi.USER_INPUT_BACKGROUND} now",
        )
    }

    @Test
    fun rendersPrefixMatchedCommandAsSelectedMenuItem() {
        val output = RecordingOutput()
        val suggestions = listOf(
            LineSuggestion("/help", "Show help"),
            LineSuggestion("/exit", "Quit"),
        )
        val snapshot = LineEditorSnapshot(
            text = "/ex",
            cursor = 3,
            suggestions = suggestions,
            selectedSuggestionIndex = 1,
            recognizedCommandEnd = null,
        )

        val rows = InputBox.renderStaticEditor(output, Theme(enabled = true), snapshot, previousMenuRows = 0)

        assertContains(output.content, "${Ansi.BOLD}${Ansi.BLUE}/exit")
        assertContains(output.content, Ansi.cursorUp(suggestions.size + 1))
        kotlin.test.assertEquals(suggestions.size, rows)
    }

    @Test
    fun positionsFixedInputCursorAfterWideCharacters() {
        val output = RecordingOutput()
        val layout = TerminalLayout.of(rows = 40, columns = 80, fixedInput = true)
        val snapshot = LineEditorSnapshot(
            text = "为什么",
            cursor = 3,
            suggestions = emptyList(),
            selectedSuggestionIndex = null,
            recognizedCommandEnd = null,
        )

        InputBox.renderFixedEditor(output, layout, Theme(enabled = true), snapshot)

        assertContains(output.content, Ansi.cursor(layout.compactInputRow, 10))
    }

    @Test
    fun rendersSubmittedUserMessageAsBackgroundBlockWithLeftBorder() {
        val output = RecordingOutput()

        InputBox.renderSubmittedMessage(output, Theme(enabled = true), "你好", width = 24)

        assertContains(
            output.content,
            "${Ansi.USER_INPUT_BORDER}${Ansi.USER_INPUT_BACKGROUND}┃${Ansi.RESET}",
        )
        assertContains(
            output.content,
            "${Ansi.USER_INPUT_BORDER}${Ansi.USER_INPUT_BACKGROUND}╻${Ansi.RESET}",
        )
        assertContains(
            output.content,
            "${Ansi.USER_INPUT_BORDER}${Ansi.USER_INPUT_BACKGROUND}╹${Ansi.RESET}",
        )
        assertContains(output.content, "${Ansi.USER_INPUT_BACKGROUND_FILL}▄")
        assertContains(output.content, "${Ansi.USER_INPUT_BACKGROUND_FILL}▀")
        assertContains(output.content, "${Ansi.USER_INPUT_BACKGROUND}  你好")
        assertTrue(output.content.lines().size >= 4)
    }

    @Test
    fun rendersReadableSubmittedMessageWithoutAnsi() {
        val output = RecordingOutput()

        InputBox.renderSubmittedMessage(output, Theme(enabled = false), "你好", width = 24)

        assertEquals("╻\n┃  你好\n╹\n", output.content)
    }

    @Test
    fun fixedOutputScrollRegionUsesAllRowsAboveClosedInputBox() {
        val output = RecordingOutput()
        val layout = TerminalLayout.of(
            rows = 40,
            columns = 80,
            fixedInput = true,
            commandMenuRows = 6,
        )

        InputBox.enterFixedLayout(output, layout)

        assertEquals(layout.inputTopRow - 1, layout.outputBottomRow)
        assertEquals(layout.menuTopRow - 1, layout.outputBottomRowForMenu(layout.commandMenuRows))
        assertContains(output.content, Ansi.ENTER_ALTERNATE_SCREEN)
        assertContains(output.content, Ansi.DISABLE_MOUSE_TRACKING)
        assertContains(output.content, Ansi.DISABLE_ALTERNATE_SCROLL)
        assertContains(output.content, Ansi.scrollRegion(1, layout.followOutputBottomRow))
        assertTrue(output.content.startsWith(Ansi.BLINKING_BAR_CURSOR))
    }

    @Test
    fun alternateScrollIsEnabledOnlyWhileReadingInput() {
        val output = RecordingOutput()

        InputBox.enableInputScrolling(output)
        InputBox.disableInputScrolling(output)

        assertEquals(Ansi.ENABLE_ALTERNATE_SCROLL + Ansi.DISABLE_ALTERNATE_SCROLL, output.content)
    }

    @Test
    fun streamingOutputCanTemporarilyResumeAndPauseAtSavedCursor() {
        val output = RecordingOutput()

        InputBox.resumeFixedOutput(output)
        InputBox.pauseFixedOutput(output)

        assertEquals(Ansi.RESTORE_CURSOR + Ansi.SAVE_CURSOR, output.content)
    }

    @Test
    fun leavingFixedLayoutRestoresTerminalScreenAndDefaultCursorStyle() {
        val output = RecordingOutput()
        val layout = TerminalLayout.of(rows = 40, columns = 80, fixedInput = true)

        InputBox.leaveFixedLayout(output, layout)

        assertContains(output.content, Ansi.RESET_CURSOR_STYLE)
        assertContains(output.content, Ansi.DISABLE_MOUSE_TRACKING)
        assertContains(output.content, Ansi.DISABLE_ALTERNATE_SCROLL)
        assertTrue(output.content.endsWith(Ansi.LEAVE_ALTERNATE_SCREEN))
    }

    @Test
    fun resizingFixedLayoutUpdatesScrollRegionAndClearsInputAreas() {
        val output = RecordingOutput()
        val oldLayout = TerminalLayout.of(
            rows = 40,
            columns = 80,
            fixedInput = true,
            commandMenuRows = 6,
        )
        val newLayout = TerminalLayout.of(
            rows = 30,
            columns = 100,
            fixedInput = true,
            commandMenuRows = 6,
        )

        InputBox.resizeFixedLayout(output, oldLayout, newLayout, previousMenuRows = 1)

        assertContains(output.content, Ansi.scrollRegion(1, newLayout.followOutputBottomRow))
        assertContains(output.content, "${Ansi.cursor(oldLayout.inputTopRow - 1, 1)}${Ansi.CLEAR_LINE}")
        assertContains(output.content, "${Ansi.cursor(newLayout.inputTopRow - 1, 1)}${Ansi.CLEAR_LINE}")
    }

    @Test
    fun rendersFixedCommandMenuAboveInputBox() {
        val output = RecordingOutput()
        val suggestions = listOf(
            LineSuggestion("/help", "Show help"),
            LineSuggestion("/exit", "Quit"),
        )
        val layout = TerminalLayout.of(
            rows = 40,
            columns = 80,
            fixedInput = true,
            commandMenuRows = suggestions.size,
        )
        val snapshot = LineEditorSnapshot(
            text = "/",
            cursor = 1,
            suggestions = suggestions,
            selectedSuggestionIndex = 0,
            recognizedCommandEnd = null,
        )

        val rows = InputBox.renderFixedEditor(output, layout, Theme(enabled = true), snapshot)

        assertEquals(suggestions.size, rows)
        assertTrue(layout.menuTopRow < layout.inputTopRow)
        assertTrue(output.content.indexOf(Ansi.cursor(layout.menuTopRow, 1)) <
            output.content.indexOf(Ansi.cursor(layout.inputTopRow, 1)))
        assertContains(output.content, "${Ansi.BOLD}${Ansi.BLUE}/help")
    }

    @Test
    fun rendersFilteredFixedCommandMenuAdjacentToInputBox() {
        val output = RecordingOutput()
        val suggestions = listOf(
            LineSuggestion("/exit", "Quit"),
        )
        val layout = TerminalLayout.of(
            rows = 40,
            columns = 80,
            fixedInput = true,
            commandMenuRows = 6,
        )
        val snapshot = LineEditorSnapshot(
            text = "/ex",
            cursor = 3,
            suggestions = suggestions,
            selectedSuggestionIndex = 0,
            recognizedCommandEnd = null,
        )

        val rows = InputBox.renderFixedEditor(output, layout, Theme(enabled = true), snapshot)

        assertEquals(suggestions.size, rows)
        val adjacentMenuCursor = Ansi.cursor(layout.inputTopRow - suggestions.size, 1)
        assertContains(output.content, adjacentMenuCursor)
        assertTrue(output.content.lastIndexOf(adjacentMenuCursor) <
            output.content.indexOf("${Ansi.BOLD}${Ansi.BLUE}/exit"))
        assertContains(output.content, "${Ansi.BOLD}${Ansi.BLUE}/exit")
    }
}

private class RecordingOutput : Output {
    private val buffer = StringBuilder()
    val content: String get() = buffer.toString()

    override fun write(text: String) {
        buffer.append(text)
    }

    override fun writeLine(text: String) {
        buffer.append(text).append('\n')
    }

    override fun flush() = Unit
}

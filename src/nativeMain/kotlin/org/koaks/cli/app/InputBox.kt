package org.koaks.cli.app

import org.koaks.cli.tui.Ansi
import org.koaks.cli.tui.LineEditorSnapshot
import org.koaks.cli.tui.Output
import org.koaks.cli.tui.TerminalLayout
import org.koaks.cli.tui.TextUtil
import org.koaks.cli.tui.Theme

internal object InputBox {
    fun renderStaticStart(output: Output, theme: Theme, commandMenuRows: Int = 0) {
        if (theme.enabled) output.write(Ansi.BLINKING_BAR_CURSOR)
        output.writeLine()
        repeat(commandMenuRows.coerceAtLeast(0)) { output.writeLine() }
        output.writeLine(inputBlockPaddingRow(theme, PANEL_WIDTH, InputPaddingEdge.TOP))
        output.write(inputBlockRow("", theme, PANEL_WIDTH))
        if (theme.enabled) output.write(Ansi.cursorColumn(INPUT_TEXT_COLUMN))
    }

    fun renderStaticEnd(output: Output, theme: Theme, inputWasEchoed: Boolean) {
        if (!inputWasEchoed) output.writeLine()
        output.writeLine(inputBlockPaddingRow(theme, PANEL_WIDTH, InputPaddingEdge.BOTTOM))
        if (theme.enabled) output.write(Ansi.RESET_CURSOR_STYLE)
    }

    fun renderStaticEditor(
        output: Output,
        theme: Theme,
        snapshot: LineEditorSnapshot,
        previousMenuRows: Int,
    ): Int {
        val menuLines = commandMenuLines(snapshot, theme, PANEL_WIDTH - 2, snapshot.suggestions.size)
        val rowsToClear = maxOf(previousMenuRows, menuLines.size)
        output.write("\r${Ansi.CLEAR_LINE}${inputContent(snapshot, theme, PANEL_WIDTH)}")
        if (rowsToClear > 0) {
            output.write(Ansi.cursorUp(rowsToClear + 1))
            val firstMenuLineIndex = rowsToClear - menuLines.size
            repeat(rowsToClear) { index ->
                output.write("\r${Ansi.CLEAR_LINE}")
                if (index >= firstMenuLineIndex) output.write(menuLines[index - firstMenuLineIndex])
                if (index < rowsToClear - 1) output.write(Ansi.cursorDown(1))
            }
            output.write(Ansi.cursorDown(2))
        }
        output.write(Ansi.cursorColumn(inputCursorColumn(snapshot)))
        return menuLines.size
    }

    fun renderStaticInteractiveEnd(
        output: Output,
        theme: Theme,
        snapshot: LineEditorSnapshot,
        menuRows: Int,
    ) {
        output.write("\r${Ansi.CLEAR_LINE}${inputContent(snapshot, theme, PANEL_WIDTH)}")
        if (menuRows > 0) {
            output.write(Ansi.cursorUp(menuRows + 1))
            repeat(menuRows) { index ->
                output.write("\r${Ansi.CLEAR_LINE}")
                if (index < menuRows - 1) output.write(Ansi.cursorDown(1))
            }
            output.write(Ansi.cursorDown(2))
        }
        output.write("\r${Ansi.CLEAR_LINE}")
        output.writeLine(inputContent(snapshot, theme, PANEL_WIDTH))
        output.writeLine(inputBlockPaddingRow(theme, PANEL_WIDTH, InputPaddingEdge.BOTTOM))
        if (theme.enabled) output.write(Ansi.RESET_CURSOR_STYLE)
    }

    fun enterFixedLayout(output: Output, layout: TerminalLayout) {
        output.write(
            Ansi.BLINKING_BAR_CURSOR +
                Ansi.ENTER_ALTERNATE_SCREEN +
                Ansi.DISABLE_MOUSE_TRACKING +
                Ansi.DISABLE_ALTERNATE_SCROLL +
                Ansi.CLEAR_SCREEN +
                Ansi.HOME +
                Ansi.scrollRegion(1, layout.followOutputBottomRow),
        )
    }

    fun enableInputScrolling(output: Output) {
        output.write(Ansi.ENABLE_ALTERNATE_SCROLL)
    }

    fun disableInputScrolling(output: Output) {
        output.write(Ansi.DISABLE_ALTERNATE_SCROLL)
    }

    fun resumeFixedOutput(output: Output) {
        output.write(Ansi.RESTORE_CURSOR)
    }

    fun pauseFixedOutput(output: Output) {
        output.write(Ansi.SAVE_CURSOR)
    }

    fun leaveFixedLayout(output: Output, layout: TerminalLayout) {
        output.write(
            Ansi.RESET_SCROLL_REGION +
                Ansi.cursor(layout.rows, 1) +
                Ansi.RESET_CURSOR_STYLE +
                Ansi.RESET +
                Ansi.DISABLE_ALTERNATE_SCROLL +
                Ansi.DISABLE_MOUSE_TRACKING +
                Ansi.LEAVE_ALTERNATE_SCREEN,
        )
    }

    fun resizeFixedLayout(
        output: Output,
        oldLayout: TerminalLayout,
        newLayout: TerminalLayout,
        previousMenuRows: Int = 0,
    ) {
        output.write(Ansi.scrollRegion(1, newLayout.followOutputBottomRow))
        clearReservedInputArea(output, oldLayout, previousMenuRows)
        clearReservedInputArea(output, newLayout, previousMenuRows)
    }

    fun renderFixed(output: Output, layout: TerminalLayout, theme: Theme) {
        output.write(Ansi.SAVE_CURSOR)
        drawCompactFixedInputBox(output, layout, theme)
        output.write(Ansi.cursor(layout.compactInputRow, INPUT_TEXT_COLUMN))
    }

    fun renderFixedEditor(
        output: Output,
        layout: TerminalLayout,
        theme: Theme,
        snapshot: LineEditorSnapshot,
        previousMenuRows: Int = 0,
    ): Int {
        val menuLines = commandMenuLines(
            snapshot,
            theme,
            layout.columns - INPUT_BORDER_WIDTH - COMMAND_MENU_GAP_WIDTH,
            layout.commandMenuRows,
        )
        val menuRowsToClear = maxOf(previousMenuRows, menuLines.size)
        clearReservedInputArea(output, layout, menuRowsToClear)
        if (menuLines.isNotEmpty()) {
            drawExpandedFixedInputBox(output, layout, theme, snapshot, menuLines)
            output.write(Ansi.cursor(layout.inputRow, inputCursorColumn(snapshot)))
        } else {
            drawCompactFixedInputBox(output, layout, theme, snapshot)
            output.write(Ansi.cursor(layout.compactInputRow, inputCursorColumn(snapshot)))
        }
        return menuLines.size
    }

    fun positionFixedEditorCursor(
        output: Output,
        layout: TerminalLayout,
        snapshot: LineEditorSnapshot,
        menuRows: Int,
    ) {
        val row = if (menuRows > 0) layout.inputRow else layout.compactInputRow
        output.write(Ansi.cursor(row, inputCursorColumn(snapshot)))
    }

    fun restoreOutputCursor(output: Output, layout: TerminalLayout, theme: Theme, menuRows: Int = 0) {
        output.write(Ansi.RESTORE_CURSOR)
        output.write(Ansi.SAVE_CURSOR)
        clearReservedInputArea(output, layout, menuRows)
        drawCompactFixedInputBox(output, layout, theme)
        output.write(Ansi.RESTORE_CURSOR)
    }

    fun renderSubmittedMessage(output: Output, theme: Theme, text: String, width: Int) {
        renderContentBlock(
            output = output,
            theme = theme,
            lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n'),
            width = width,
        )
    }

    fun renderContentBlock(
        output: Output,
        theme: Theme,
        lines: List<String>,
        width: Int,
        accent: BlockAccent = BlockAccent.USER_INPUT,
    ) {
        val safeWidth = width.coerceAtLeast(MIN_INPUT_BLOCK_WIDTH)
        output.writeLine(inputBlockPaddingRow(theme, safeWidth, InputPaddingEdge.TOP, accent))
        lines.forEach { line -> output.writeLine(inputBlockRow(line, theme, safeWidth, accent)) }
        output.writeLine(inputBlockPaddingRow(theme, safeWidth, InputPaddingEdge.BOTTOM, accent))
    }

    private fun clearReservedInputArea(output: Output, layout: TerminalLayout, menuRows: Int) {
        val topRow = (layout.inputTopRow - menuRows).coerceAtLeast(1)
        for (row in topRow..layout.inputBottomRow) {
            output.write("${Ansi.cursor(row, 1)}${Ansi.CLEAR_LINE}")
        }
    }

    private fun drawCompactFixedInputBox(
        output: Output,
        layout: TerminalLayout,
        theme: Theme,
        snapshot: LineEditorSnapshot? = null,
    ) {
        val width = inputBlockWidth(layout)
        output.write(
            "${Ansi.cursor(layout.compactInputTopRow, 1)}${Ansi.CLEAR_LINE}" +
                inputBlockPaddingRow(theme, width, InputPaddingEdge.TOP),
        )
        output.write("${Ansi.cursor(layout.compactInputRow, 1)}${Ansi.CLEAR_LINE}")
        if (snapshot == null) {
            output.write(inputBlockRow("", theme, width))
        } else {
            output.write(inputContent(snapshot, theme, width))
        }
        output.write(
            "${Ansi.cursor(layout.inputBottomRow, 1)}${Ansi.CLEAR_LINE}" +
                inputBlockPaddingRow(theme, width, InputPaddingEdge.BOTTOM),
        )
    }

    private fun drawExpandedFixedInputBox(
        output: Output,
        layout: TerminalLayout,
        theme: Theme,
        snapshot: LineEditorSnapshot,
        menuLines: List<String>,
    ) {
        val width = inputBlockWidth(layout)
        val menuTopRow = layout.inputTopRow - menuLines.size
        menuLines.forEachIndexed { index, menuLine ->
            output.write(Ansi.cursor(menuTopRow + index, 1))
            output.write("${theme.inputSide()} $menuLine")
        }
        output.write(
            "${Ansi.cursor(layout.inputTopRow, 1)}" +
                inputBlockPaddingRow(theme, width, InputPaddingEdge.TOP),
        )
        output.write("${Ansi.cursor(layout.inputRow, 1)}${inputContent(snapshot, theme, width)}")
        output.write(
            "${Ansi.cursor(layout.inputBottomRow, 1)}" +
                inputBlockPaddingRow(theme, width, InputPaddingEdge.BOTTOM),
        )
    }

    private fun inputContent(snapshot: LineEditorSnapshot, theme: Theme, width: Int): String {
        val content = buildString {
            val commandEnd = snapshot.recognizedCommandEnd
            if (commandEnd == null) {
                append(snapshot.text)
            } else {
                append(theme.inputCommand(snapshot.text.substring(0, commandEnd)))
                append(snapshot.text.substring(commandEnd))
            }
        }
        return inputBlockRow(content, theme, width)
    }

    private fun inputBlockRow(
        content: String,
        theme: Theme,
        width: Int,
        accent: BlockAccent = BlockAccent.USER_INPUT,
    ): String {
        val body = "  $content"
        val side = when (accent) {
            BlockAccent.USER_INPUT -> theme.inputSide()
            BlockAccent.WELCOME -> theme.welcomeSide()
        }
        if (!theme.enabled) return side + body

        val interiorWidth = (width - INPUT_BORDER_WIDTH).coerceAtLeast(1)
        val clipped = TextUtil.truncateVisible(body, interiorWidth)
        val padding = TextUtil.rule(' ', interiorWidth - TextUtil.visibleWidth(clipped))
        return side + theme.inputBackground(clipped) + theme.inputBackground(padding)
    }

    private fun inputBlockPaddingRow(
        theme: Theme,
        width: Int,
        edge: InputPaddingEdge,
        accent: BlockAccent = BlockAccent.USER_INPUT,
    ): String {
        val (border, fill) = when (edge) {
            InputPaddingEdge.TOP -> "╻" to "▄"
            InputPaddingEdge.BOTTOM -> "╹" to "▀"
        }
        val side = when (accent) {
            BlockAccent.USER_INPUT -> theme.inputPaddingSide(border)
            BlockAccent.WELCOME -> theme.welcomePaddingSide(border)
        }
        if (!theme.enabled) return side

        val interiorWidth = (width - INPUT_BORDER_WIDTH).coerceAtLeast(1)
        return side + theme.inputBackgroundFill(fill.repeat(interiorWidth))
    }

    private fun commandMenuLines(
        snapshot: LineEditorSnapshot,
        theme: Theme,
        width: Int,
        capacity: Int,
    ): List<String> {
        if (!snapshot.menuVisible || capacity <= 0) return emptyList()
        val suggestions = snapshot.suggestions
        val selected = snapshot.selectedSuggestionIndex
        val start = when {
            suggestions.size <= capacity -> 0
            selected == null -> 0
            selected < capacity -> 0
            else -> (selected - capacity + 1).coerceAtMost(suggestions.size - capacity)
        }
        val commandWidth = suggestions.maxOfOrNull { it.value.length } ?: 0
        return suggestions.drop(start).take(capacity).mapIndexed { offset, suggestion ->
            val index = start + offset
            val marker = if (index == selected) theme.commandMenuSelection("›") else " "
            val command = if (index == selected) {
                theme.commandMenuSelection(suggestion.value.padEnd(commandWidth))
            } else {
                theme.command(suggestion.value.padEnd(commandWidth))
            }
            val description = theme.dim(suggestion.description)
            TextUtil.truncateVisible("$marker $command  $description", width)
        }
    }

    private fun inputCursorColumn(snapshot: LineEditorSnapshot): Int =
        INPUT_TEXT_COLUMN + TextUtil.visibleWidth(snapshot.text.substring(0, snapshot.cursor))

    private fun inputBlockWidth(layout: TerminalLayout): Int =
        (layout.columns - 1).coerceAtLeast(MIN_INPUT_BLOCK_WIDTH)

    private enum class InputPaddingEdge {
        TOP,
        BOTTOM,
    }

    enum class BlockAccent {
        USER_INPUT,
        WELCOME,
    }

    private const val INPUT_BORDER_WIDTH = 1
    private const val INPUT_TEXT_COLUMN = 4
    private const val COMMAND_MENU_GAP_WIDTH = 1
    private const val MIN_INPUT_BLOCK_WIDTH = 8
}

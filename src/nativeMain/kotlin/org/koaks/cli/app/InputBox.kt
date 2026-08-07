package org.koaks.cli.app

import org.koaks.cli.tui.Ansi
import org.koaks.cli.tui.LineEditorSnapshot
import org.koaks.cli.tui.MAX_INPUT_TEXT_ROWS
import org.koaks.cli.tui.Output
import org.koaks.cli.tui.TerminalLayout
import org.koaks.cli.tui.TextUtil
import org.koaks.cli.tui.Theme

internal object InputBox {
    fun editorTextWidth(layout: TerminalLayout): Int =
        inputTextWidth(if (layout.fixedInput) inputBlockWidth(layout) else PANEL_WIDTH)

    fun editorTextRows(snapshot: LineEditorSnapshot, layout: TerminalLayout): Int =
        fixedInputDisplay(snapshot, inputBlockWidth(layout)).rows.size

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
        output.write("\r${Ansi.CLEAR_LINE}${staticInputContent(snapshot, theme, PANEL_WIDTH)}")
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
        output.write(Ansi.cursorColumn(inputCursorColumn(snapshot, PANEL_WIDTH)))
        return menuLines.size
    }

    fun renderStaticInteractiveEnd(
        output: Output,
        theme: Theme,
        snapshot: LineEditorSnapshot,
        menuRows: Int,
    ) {
        output.write("\r${Ansi.CLEAR_LINE}${staticInputContent(snapshot, theme, PANEL_WIDTH)}")
        if (menuRows > 0) {
            output.write(Ansi.cursorUp(menuRows + 1))
            repeat(menuRows) { index ->
                output.write("\r${Ansi.CLEAR_LINE}")
                if (index < menuRows - 1) output.write(Ansi.cursorDown(1))
            }
            output.write(Ansi.cursorDown(2))
        }
        output.write("\r${Ansi.CLEAR_LINE}")
        output.writeLine(staticInputContent(snapshot, theme, PANEL_WIDTH))
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
                Ansi.SHOW_CURSOR +
                Ansi.DISABLE_ALTERNATE_SCROLL +
                Ansi.DISABLE_MODIFY_OTHER_KEYS +
                Ansi.DISABLE_BRACKETED_PASTE +
                Ansi.DISABLE_MOUSE_TRACKING +
                Ansi.LEAVE_ALTERNATE_SCREEN,
        )
    }

    fun resizeFixedLayout(
        output: Output,
        oldLayout: TerminalLayout,
        newLayout: TerminalLayout,
        previousMenuRows: Int = 0,
        previousInputRows: Int = 1,
    ) {
        updateFixedOutputRegion(output, newLayout, previousMenuRows, previousInputRows)
        clearInputArea(output, oldLayout, inputAreaTopRow(oldLayout, previousMenuRows, previousInputRows))
        clearInputArea(output, newLayout, inputAreaTopRow(newLayout, previousMenuRows, previousInputRows))
    }

    fun updateFixedOutputRegion(
        output: Output,
        layout: TerminalLayout,
        menuRows: Int = 0,
        inputRows: Int = 1,
    ) {
        output.write(Ansi.scrollRegion(1, layout.followOutputBottomRowFor(menuRows, inputRows)))
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
        previousInputRows: Int = 1,
    ): Int {
        val width = inputBlockWidth(layout)
        val display = fixedInputDisplay(snapshot, width)
        val menuLines = commandMenuLines(
            snapshot,
            theme,
            layout.columns - INPUT_BORDER_WIDTH - COMMAND_MENU_GAP_WIDTH,
            layout.commandMenuRows,
        )
        val previousTopRow = inputAreaTopRow(layout, previousMenuRows, previousInputRows)
        val currentTopRow = display.boxTopRow(layout) - menuLines.size
        clearInputArea(output, layout, minOf(previousTopRow, currentTopRow))
        drawFixedInputBox(output, layout, theme, snapshot, display, menuLines)
        output.write(Ansi.cursor(display.cursorRow(layout), INPUT_TEXT_COLUMN + display.cursorColumn))
        return menuLines.size
    }

    fun positionFixedEditorCursor(
        output: Output,
        layout: TerminalLayout,
        snapshot: LineEditorSnapshot,
        menuRows: Int,
    ) {
        val display = fixedInputDisplay(snapshot, inputBlockWidth(layout))
        output.write(Ansi.cursor(display.cursorRow(layout), INPUT_TEXT_COLUMN + display.cursorColumn))
    }

    fun restoreOutputCursor(
        output: Output,
        layout: TerminalLayout,
        theme: Theme,
        menuRows: Int = 0,
        inputRows: Int = 1,
    ) {
        output.write(Ansi.RESTORE_CURSOR)
        output.write(Ansi.SAVE_CURSOR)
        updateFixedOutputRegion(output, layout)
        clearInputArea(output, layout, inputAreaTopRow(layout, menuRows, inputRows))
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
        lines.forEach { line ->
            wrappedTextRows(line, inputTextWidth(safeWidth)).forEach { row ->
                output.writeLine(
                    inputBlockRow(line.substring(row.start, row.end), theme, safeWidth, accent),
                )
            }
        }
        output.writeLine(inputBlockPaddingRow(theme, safeWidth, InputPaddingEdge.BOTTOM, accent))
    }

    private fun clearInputArea(output: Output, layout: TerminalLayout, topRow: Int) {
        for (row in topRow..layout.inputBottomRow) {
            output.write("${Ansi.cursor(row, 1)}${Ansi.CLEAR_LINE}")
        }
    }

    private fun inputAreaTopRow(layout: TerminalLayout, menuRows: Int, inputRows: Int): Int =
        (layout.inputBottomRow - inputRows.coerceIn(1, MAX_INPUT_TEXT_ROWS) - 1 - menuRows)
            .coerceAtLeast(1)

    private fun drawCompactFixedInputBox(
        output: Output,
        layout: TerminalLayout,
        theme: Theme,
        snapshot: LineEditorSnapshot? = null,
    ) {
        val width = inputBlockWidth(layout)
        val display = snapshot?.let { fixedInputDisplay(it, width) } ?: FixedInputDisplay.empty()
        drawFixedInputBox(output, layout, theme, snapshot, display, emptyList())
    }

    private fun drawFixedInputBox(
        output: Output,
        layout: TerminalLayout,
        theme: Theme,
        snapshot: LineEditorSnapshot?,
        display: FixedInputDisplay,
        menuLines: List<String>,
    ) {
        val width = inputBlockWidth(layout)
        val boxTopRow = display.boxTopRow(layout)
        val menuTopRow = boxTopRow - menuLines.size
        menuLines.forEachIndexed { index, menuLine ->
            output.write(Ansi.cursor(menuTopRow + index, 1))
            output.write("${theme.inputSide()} $menuLine")
        }
        output.write(
            "${Ansi.cursor(boxTopRow, 1)}${Ansi.CLEAR_LINE}" +
                inputBlockPaddingRow(theme, width, InputPaddingEdge.TOP),
        )
        display.rows.forEachIndexed { index, row ->
            val content = if (snapshot == null) {
                inputBlockRow("", theme, width)
            } else {
                inputContent(snapshot, row, theme, width)
            }
            output.write("${Ansi.cursor(boxTopRow + index + 1, 1)}${Ansi.CLEAR_LINE}$content")
        }
        output.write(
            "${Ansi.cursor(layout.inputBottomRow, 1)}${Ansi.CLEAR_LINE}" +
                inputBlockPaddingRow(theme, width, InputPaddingEdge.BOTTOM),
        )
    }

    private fun inputContent(snapshot: LineEditorSnapshot, row: WrappedTextRow, theme: Theme, width: Int): String {
        val displayText = snapshot.displayText
        val content = buildString {
            val commandEnd = snapshot.recognizedCommandEnd
            when {
                commandEnd == null || row.start >= commandEnd -> {
                    append(displayText.substring(row.start, row.end))
                }

                row.end <= commandEnd -> {
                    append(theme.inputCommand(displayText.substring(row.start, row.end)))
                }

                else -> {
                    append(theme.inputCommand(displayText.substring(row.start, commandEnd)))
                    append(displayText.substring(commandEnd, row.end))
                }
            }
        }
        return inputBlockRow(content, theme, width)
    }

    private fun staticInputContent(snapshot: LineEditorSnapshot, theme: Theme, width: Int): String {
        val displayText = snapshot.displayText
        val viewport = inputViewport(displayText, snapshot.displayCursor, inputTextWidth(width))
        val content = buildString {
            val commandEnd = snapshot.recognizedCommandEnd
            when {
                commandEnd == null || viewport.start >= commandEnd -> {
                    append(displayText.substring(viewport.start, viewport.end))
                }

                viewport.end <= commandEnd -> {
                    append(theme.inputCommand(displayText.substring(viewport.start, viewport.end)))
                }

                else -> {
                    append(theme.inputCommand(displayText.substring(viewport.start, commandEnd)))
                    append(displayText.substring(commandEnd, viewport.end))
                }
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

    private fun inputCursorColumn(snapshot: LineEditorSnapshot, width: Int): Int {
        val viewport = inputViewport(snapshot.displayText, snapshot.displayCursor, inputTextWidth(width))
        return INPUT_TEXT_COLUMN + viewport.cursorOffset
    }

    private fun inputTextWidth(width: Int): Int =
        (width - INPUT_BORDER_WIDTH - INPUT_TEXT_PADDING_WIDTH).coerceAtLeast(1)

    private fun inputViewport(text: String, cursor: Int, width: Int): InputViewport {
        val safeCursor = cursor.coerceIn(0, text.length)
        val safeWidth = width.coerceAtLeast(1)
        val lineStart = if (safeCursor == 0) 0 else text.lastIndexOf('\n', safeCursor - 1) + 1
        val lineEnd = text.indexOf('\n', safeCursor).let { if (it < 0) text.length else it }
        var start = safeCursor
        var end = safeCursor
        var visibleWidth = 0
        val preferredLeftWidth = safeWidth / 2

        while (start > lineStart) {
            val previous = previousCharacterIndex(text, start)
            val characterWidth = TextUtil.visibleWidth(text.substring(previous, start))
            if (characterWidth > 0 && visibleWidth + characterWidth > preferredLeftWidth) break
            start = previous
            visibleWidth += characterWidth
        }

        while (end < lineEnd) {
            val next = nextCharacterIndex(text, end)
            val characterWidth = TextUtil.visibleWidth(text.substring(end, next))
            if (characterWidth > 0 && visibleWidth + characterWidth > safeWidth) break
            end = next
            visibleWidth += characterWidth
        }

        while (start > lineStart) {
            val previous = previousCharacterIndex(text, start)
            val characterWidth = TextUtil.visibleWidth(text.substring(previous, start))
            if (characterWidth > 0 && visibleWidth + characterWidth > safeWidth) break
            start = previous
            visibleWidth += characterWidth
        }

        return InputViewport(
            start = start,
            end = end,
            cursorOffset = TextUtil.visibleWidth(text.substring(start, safeCursor)),
        )
    }

    private fun fixedInputDisplay(snapshot: LineEditorSnapshot, width: Int): FixedInputDisplay {
        val displayText = snapshot.displayText
        val rows = wrappedTextRows(displayText, inputTextWidth(width))
        val cursor = snapshot.displayCursor.coerceIn(0, displayText.length)
        val cursorRow = rows.indexOfFirst { row ->
            cursor < row.end ||
                (cursor == row.end && (row.end == displayText.length || displayText.getOrNull(row.end) == '\n'))
        }
            .takeIf { it >= 0 }
            ?: rows.lastIndex
        val firstVisibleRow = (cursorRow - 1).coerceIn(
            minimumValue = 0,
            maximumValue = (rows.size - MAX_INPUT_TEXT_ROWS).coerceAtLeast(0),
        )
        val visibleRows = rows.drop(firstVisibleRow).take(MAX_INPUT_TEXT_ROWS)
        val activeRow = rows[cursorRow]
        return FixedInputDisplay(
            rows = visibleRows,
            cursorVisibleRow = cursorRow - firstVisibleRow,
            cursorColumn = TextUtil.visibleWidth(displayText.substring(activeRow.start, cursor)),
        )
    }

    private fun wrappedTextRows(text: String, width: Int): List<WrappedTextRow> {
        if (text.isEmpty()) return listOf(WrappedTextRow(0, 0))

        val safeWidth = width.coerceAtLeast(1)
        val rows = mutableListOf<WrappedTextRow>()
        var rowStart = 0
        var rowWidth = 0
        var index = 0
        while (index < text.length) {
            if (text[index] == '\n') {
                rows += WrappedTextRow(rowStart, index)
                rowStart = index + 1
                rowWidth = 0
                index++
                continue
            }
            val next = nextCharacterIndex(text, index)
            val characterWidth = TextUtil.visibleWidth(text.substring(index, next))
            if (characterWidth > 0 && rowWidth > 0 && rowWidth + characterWidth > safeWidth) {
                rows += WrappedTextRow(rowStart, index)
                rowStart = index
                rowWidth = 0
            }
            rowWidth += characterWidth
            index = next
        }
        rows += WrappedTextRow(rowStart, text.length)
        return rows
    }

    private fun previousCharacterIndex(text: String, index: Int): Int {
        val previous = (index - 1).coerceAtLeast(0)
        return if (previous > 0 && text[previous].isLowSurrogate() && text[previous - 1].isHighSurrogate()) {
            previous - 1
        } else {
            previous
        }
    }

    private fun nextCharacterIndex(text: String, index: Int): Int {
        if (index >= text.length) return text.length
        return if (index + 1 < text.length && text[index].isHighSurrogate() && text[index + 1].isLowSurrogate()) {
            index + 2
        } else {
            index + 1
        }
    }

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

    private data class InputViewport(
        val start: Int,
        val end: Int,
        val cursorOffset: Int,
    )

    private data class WrappedTextRow(val start: Int, val end: Int)

    private data class FixedInputDisplay(
        val rows: List<WrappedTextRow>,
        val cursorVisibleRow: Int,
        val cursorColumn: Int,
    ) {
        fun boxTopRow(layout: TerminalLayout): Int =
            layout.inputBottomRow - rows.size - 1

        fun cursorRow(layout: TerminalLayout): Int =
            boxTopRow(layout) + cursorVisibleRow + 1

        companion object {
            fun empty(): FixedInputDisplay = FixedInputDisplay(
                rows = listOf(WrappedTextRow(0, 0)),
                cursorVisibleRow = 0,
                cursorColumn = 0,
            )
        }
    }

    private const val INPUT_BORDER_WIDTH = 1
    private const val INPUT_TEXT_COLUMN = 4
    private const val INPUT_TEXT_PADDING_WIDTH = 2
    private const val COMMAND_MENU_GAP_WIDTH = 1
    private const val MIN_INPUT_BLOCK_WIDTH = 8
}

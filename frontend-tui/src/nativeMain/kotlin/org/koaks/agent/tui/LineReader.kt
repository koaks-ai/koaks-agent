package org.koaks.agent.tui

internal interface LineReader {
    fun readLine(): String?

    fun readLine(request: LineReadRequest): String? = readLine()
}

internal object StdinLineReader : LineReader {
    override fun readLine(): String? = readlnOrNull()

    override fun readLine(request: LineReadRequest): String? {
        if (!NativeTerminalInput.enterRawMode()) return readLine()

        try {
            request.onInteractiveStart()
            val editor = LineEditor(request)
            request.onUpdate(editor.snapshot())

            while (true) {
                val key = NativeTerminalInput.readKey()
                if (request.onKey(key)) {
                    request.onUpdate(editor.snapshot())
                    continue
                }
                when (val result = editor.accept(key)) {
                    LineEditResult.Continue -> request.onUpdate(editor.snapshot())
                    is LineEditResult.Scroll -> {
                        request.onScroll(result.rows)
                        request.onUpdate(editor.snapshot())
                    }
                    is LineEditResult.Submit -> {
                        request.onUpdate(editor.snapshot())
                        return result.text
                    }
                    LineEditResult.EndOfInput -> return null
                }
            }
        } finally {
            try {
                request.onInteractiveEnd()
            } finally {
                NativeTerminalInput.leaveRawMode()
            }
        }
    }
}

internal data class LineSuggestion(
    val value: String,
    val description: String,
)

internal data class LineReadRequest(
    val suggestions: List<LineSuggestion>,
    val commandNames: Set<String>,
    val scrollPageRows: Int = DEFAULT_SCROLL_PAGE_ROWS,
    val inputWidth: () -> Int = { Int.MAX_VALUE },
    val onKey: (TerminalKey) -> Boolean = { false },
    val onScroll: (Int) -> Unit = {},
    val onInteractiveStart: () -> Unit = {},
    val onInteractiveEnd: () -> Unit = {},
    val onUpdate: (LineEditorSnapshot) -> Unit,
)

internal data class LineEditorSnapshot(
    val text: String,
    val cursor: Int,
    val suggestions: List<LineSuggestion>,
    val selectedSuggestionIndex: Int?,
    val recognizedCommandEnd: Int?,
    val displayText: String = text,
    val displayCursor: Int = cursor,
    val pastedLineCount: Int? = null,
) {
    val menuVisible: Boolean
        get() = text.startsWith("/") && text.none(Char::isWhitespace)
}

internal sealed interface LineEditResult {
    object Continue : LineEditResult

    data class Scroll(
        val rows: Int,
    ) : LineEditResult

    data class Submit(
        val text: String,
    ) : LineEditResult

    object EndOfInput : LineEditResult
}

internal class LineEditor(
    private val request: LineReadRequest,
) {
    private var text: String = ""
    private var cursor: Int = 0
    private var manuallySelectedIndex: Int? = null
    private var preferredVisualColumn: Int? = null
    private var pastePreview: PastePreview? = null

    fun snapshot(): LineEditorSnapshot {
        val suggestions = currentSuggestions()
        val selectedIndex =
            if (menuVisible()) {
                manuallySelectedIndex?.coerceAtMost(suggestions.lastIndex)
                    ?: suggestions.indices.firstOrNull()
            } else {
                null
            }
        val preview = pastePreview
        val displayText = preview?.displayText(text) ?: text
        val displayCursor = preview?.displayCursor(cursor) ?: cursor
        val commandEnd = text.indexOfFirst(Char::isWhitespace).let { if (it < 0) text.length else it }
        val command = text.substring(0, commandEnd).normalizeCommandName()
        return LineEditorSnapshot(
            text = text,
            cursor = cursor,
            suggestions = suggestions,
            selectedSuggestionIndex = selectedIndex,
            recognizedCommandEnd = commandEnd.takeIf { preview == null && command in request.commandNames },
            displayText = displayText,
            displayCursor = displayCursor,
            pastedLineCount = preview?.lineCount,
        )
    }

    fun accept(key: TerminalKey): LineEditResult {
        when (key) {
            TerminalKey.Enter -> {
                val suggestion = selectedSuggestion()
                if (menuVisible() && suggestion != null) {
                    text = suggestion.value
                    cursor = text.length
                }
                return LineEditResult.Submit(text)
            }
            TerminalKey.LineBreak -> insert("\n")
            is TerminalKey.Paste -> paste(key.value)
            TerminalKey.EndOfInput -> return LineEditResult.EndOfInput
            TerminalKey.Backspace -> {
                revealPastedText()
                deleteBeforeCursor()
            }
            TerminalKey.Delete -> {
                revealPastedText()
                deleteAtCursor()
            }
            TerminalKey.Left -> {
                revealPastedText()
                cursor = previousCharacterIndex(text, cursor)
                preferredVisualColumn = null
            }
            TerminalKey.Right -> {
                revealPastedText()
                val suggestion = selectedSuggestion()
                if (cursor == text.length &&
                    menuVisible() &&
                    suggestion != null &&
                    suggestion.value.length > text.length
                ) {
                    acceptSuggestion()
                } else {
                    cursor = nextCharacterIndex(text, cursor)
                    preferredVisualColumn = null
                }
            }
            TerminalKey.Home -> {
                revealPastedText()
                cursor = 0
                preferredVisualColumn = null
            }
            TerminalKey.End -> {
                revealPastedText()
                cursor = text.length
                preferredVisualColumn = null
            }
            TerminalKey.Up -> {
                revealPastedText()
                if (menuVisible()) {
                    preferredVisualColumn = null
                    moveSelection(-1)
                } else if (!moveCursorVertically(-1)) {
                    return LineEditResult.Scroll(SCROLL_STEP_ROWS)
                }
            }
            TerminalKey.Down -> {
                revealPastedText()
                if (menuVisible()) {
                    preferredVisualColumn = null
                    moveSelection(1)
                } else if (!moveCursorVertically(1)) {
                    return LineEditResult.Scroll(-SCROLL_STEP_ROWS)
                }
            }
            TerminalKey.PageUp -> return LineEditResult.Scroll(request.scrollPageRows.coerceAtLeast(1))
            TerminalKey.PageDown -> return LineEditResult.Scroll(-request.scrollPageRows.coerceAtLeast(1))
            TerminalKey.Tab -> {
                revealPastedText()
                acceptSuggestion()
            }
            TerminalKey.Escape -> {
                revealPastedText()
                preferredVisualColumn = null
            }
            is TerminalKey.Text -> insert(key.value)
        }
        return LineEditResult.Continue
    }

    private fun insert(value: String) {
        revealPastedText()
        text = text.substring(0, cursor) + value + text.substring(cursor)
        cursor += value.length
        manuallySelectedIndex = null
        preferredVisualColumn = null
    }

    private fun deleteBeforeCursor() {
        if (cursor == 0) return
        val start = previousCharacterIndex(text, cursor)
        text = text.removeRange(start, cursor)
        cursor = start
        manuallySelectedIndex = null
        preferredVisualColumn = null
    }

    private fun deleteAtCursor() {
        if (cursor == text.length) return
        text = text.removeRange(cursor, nextCharacterIndex(text, cursor))
        manuallySelectedIndex = null
        preferredVisualColumn = null
    }

    private fun moveSelection(delta: Int) {
        val suggestions = currentSuggestions()
        if (!menuVisible() || suggestions.isEmpty()) return
        val current = selectedIndex() ?: if (delta > 0) -1 else 0
        manuallySelectedIndex = (current + delta).mod(suggestions.size)
    }

    private fun acceptSuggestion() {
        if (!menuVisible()) return
        val suggestion = selectedSuggestion() ?: return
        text = suggestion.value
        cursor = text.length
        manuallySelectedIndex = selectedIndex()
        preferredVisualColumn = null
    }

    private fun paste(value: String) {
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isEmpty()) return
        if (!normalized.contains('\n')) {
            insert(normalized)
            return
        }

        revealPastedText()
        val start = cursor
        text = text.substring(0, cursor) + normalized + text.substring(cursor)
        cursor += normalized.length
        pastePreview =
            PastePreview(
                start = start,
                end = cursor,
                lineCount = normalized.count { it == '\n' } + 1,
            )
        manuallySelectedIndex = null
        preferredVisualColumn = null
    }

    private fun revealPastedText() {
        pastePreview = null
    }

    private fun moveCursorVertically(delta: Int): Boolean {
        val rows = visualRows(request.inputWidth().coerceAtLeast(1))
        if (rows.size <= 1) return false

        val currentRow =
            rows
                .indexOfFirst { row ->
                    cursor < row.end || (cursor == row.end && (row.end == text.length || text.getOrNull(row.end) == '\n'))
                }.takeIf { it >= 0 }
                ?: rows.lastIndex
        val targetRow = (currentRow + delta).coerceIn(0, rows.lastIndex)
        val column =
            preferredVisualColumn
                ?: TextUtil.visibleWidth(text.substring(rows[currentRow].start, cursor))
        preferredVisualColumn = column
        if (targetRow == currentRow) return true

        cursor = cursorIndexAtColumn(rows[targetRow], column)
        manuallySelectedIndex = null
        return true
    }

    private fun visualRows(width: Int): List<EditorVisualRow> {
        if (text.isEmpty()) return listOf(EditorVisualRow(0, 0))

        val rows = mutableListOf<EditorVisualRow>()
        var rowStart = 0
        var rowWidth = 0
        var index = 0
        while (index < text.length) {
            if (text[index] == '\n') {
                rows += EditorVisualRow(rowStart, index)
                rowStart = index + 1
                rowWidth = 0
                index++
                continue
            }
            val next = nextCharacterIndex(text, index)
            val characterWidth = TextUtil.visibleWidth(text.substring(index, next))
            if (characterWidth > 0 && rowWidth > 0 && rowWidth + characterWidth > width) {
                rows += EditorVisualRow(rowStart, index)
                rowStart = index
                rowWidth = 0
            }
            rowWidth += characterWidth
            index = next
        }
        rows += EditorVisualRow(rowStart, text.length)
        return rows
    }

    private fun cursorIndexAtColumn(
        row: EditorVisualRow,
        column: Int,
    ): Int {
        var index = row.start
        var visibleWidth = 0
        while (index < row.end) {
            val next = nextCharacterIndex(text, index)
            val characterWidth = TextUtil.visibleWidth(text.substring(index, next))
            if (characterWidth > 0 && visibleWidth + characterWidth > column) break
            visibleWidth += characterWidth
            index = next
        }
        return index
    }

    private fun selectedIndex(): Int? = snapshot().selectedSuggestionIndex

    private fun selectedSuggestion(): LineSuggestion? {
        val suggestions = currentSuggestions()
        val index = selectedIndex() ?: return null
        return suggestions.getOrNull(index)
    }

    private fun currentSuggestions(): List<LineSuggestion> {
        if (!menuVisible()) return emptyList()
        if (text == "/") return request.suggestions

        val query = text.lowercase()
        val bareQuery = query.removePrefix("/")
        return request.suggestions
            .mapIndexedNotNull { index, suggestion ->
                val value = suggestion.value.lowercase()
                val bareValue = value.removePrefix("/")
                val score =
                    when {
                        value == query -> 0
                        value.startsWith(query) -> 1
                        bareValue == bareQuery -> 2
                        bareValue.startsWith(bareQuery) -> 3
                        value.contains(query) -> 4
                        bareValue.contains(bareQuery) -> 5
                        else -> return@mapIndexedNotNull null
                    }
                ScoredSuggestion(score, suggestion.value.length, index, suggestion)
            }.sortedWith(
                compareBy<ScoredSuggestion> { it.score }
                    .thenBy { it.length }
                    .thenBy { it.index },
            ).map { it.suggestion }
    }

    private fun menuVisible(): Boolean = text.startsWith("/") && text.none(Char::isWhitespace)
}

private data class ScoredSuggestion(
    val score: Int,
    val length: Int,
    val index: Int,
    val suggestion: LineSuggestion,
)

private data class EditorVisualRow(
    val start: Int,
    val end: Int,
)

internal sealed interface TerminalKey {
    data class Text(
        val value: String,
    ) : TerminalKey

    data class Paste(
        val value: String,
    ) : TerminalKey

    object Enter : TerminalKey

    object LineBreak : TerminalKey

    object Backspace : TerminalKey

    object Delete : TerminalKey

    object Left : TerminalKey

    object Right : TerminalKey

    object Up : TerminalKey

    object Down : TerminalKey

    object PageUp : TerminalKey

    object PageDown : TerminalKey

    object Home : TerminalKey

    object End : TerminalKey

    object Tab : TerminalKey

    object Escape : TerminalKey

    object EndOfInput : TerminalKey
}

/** Decodes Enter from CSI-u and xterm modifyOtherKeys sequences. */
internal fun decodeCsiEnterKey(sequence: String): TerminalKey? {
    if (sequence.isEmpty()) return null
    val parameters = sequence.dropLast(1).split(';')
    val modifier =
        when (sequence.last()) {
            'u' -> {
                if (parameters.firstOrNull()?.substringBefore(':')?.toIntOrNull() != ENTER_CODE) return null
                parameters.getOrNull(1)
            }
            '~' -> {
                if (parameters.firstOrNull() != XTERM_MODIFIED_KEY_PREFIX ||
                    parameters.getOrNull(2)?.substringBefore(':')?.toIntOrNull() != ENTER_CODE
                ) {
                    return null
                }
                parameters.getOrNull(1)
            }
            else -> return null
        }
    val modifierCode = modifier?.substringBefore(':')?.toIntOrNull() ?: NO_MODIFIER
    return if (((modifierCode - NO_MODIFIER) and SHIFT_MODIFIER_BIT) != 0) {
        TerminalKey.LineBreak
    } else {
        TerminalKey.Enter
    }
}

private data class PastePreview(
    val start: Int,
    val end: Int,
    val lineCount: Int,
) {
    fun displayText(text: String): String {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(safeStart, text.length)
        return text.substring(0, safeStart) + "[粘贴 $lineCount 行内容]" + text.substring(safeEnd)
    }

    fun displayCursor(cursor: Int): Int {
        val placeholderEnd = start + "[粘贴 $lineCount 行内容]".length
        return when {
            cursor <= start -> cursor
            cursor >= end -> cursor - (end - start) + (placeholderEnd - start)
            else -> placeholderEnd
        }
    }
}

internal expect object NativeTerminalInput {
    fun enterRawMode(): Boolean

    fun leaveRawMode()

    fun readKey(): TerminalKey
}

private fun String.normalizeCommandName(): String = if (startsWith("/")) lowercase() else this

private const val DEFAULT_SCROLL_PAGE_ROWS = 10
private const val SCROLL_STEP_ROWS = 1
private const val ENTER_CODE = 13
private const val NO_MODIFIER = 1
private const val SHIFT_MODIFIER_BIT = 1
private const val XTERM_MODIFIED_KEY_PREFIX = "27"

private fun previousCharacterIndex(
    text: String,
    cursor: Int,
): Int {
    if (cursor <= 0) return 0
    val previous = cursor - 1
    return if (previous > 0 && text[previous].isLowSurrogate() && text[previous - 1].isHighSurrogate()) {
        previous - 1
    } else {
        previous
    }
}

private fun nextCharacterIndex(
    text: String,
    cursor: Int,
): Int {
    if (cursor >= text.length) return text.length
    return if (cursor + 1 < text.length && text[cursor].isHighSurrogate() && text[cursor + 1].isLowSurrogate()) {
        cursor + 2
    } else {
        cursor + 1
    }
}

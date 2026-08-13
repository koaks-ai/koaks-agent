package org.koaks.agent.tui.render

import org.koaks.agent.tui.io.LiveLinesOutput
import org.koaks.agent.tui.io.Output
import org.koaks.agent.tui.io.inFrame
import org.koaks.agent.tui.state.TerminalLayout

/**
 * Records rendered output so the alternate-screen UI can provide scrollback while
 * keeping the input box pinned to the bottom of the terminal.
 */
internal class FixedTerminalViewport(
    private val terminal: Output,
    initialLayout: TerminalLayout,
) {
    private val transcript = TerminalTranscript()
    private var scrollOffsetRows = 0
    private var manualScrollActive = false
    private var knownRenderedRowCount = 0
    private var followLayout = initialLayout
    private var followMenuRows = 0
    private var followInputRows = 1
    private var followCursorRenderer: (() -> Unit)? = null

    val contentOutput: Output =
        object : LiveLinesOutput {
            override fun write(text: String) {
                transcript.append(text)
            }

            override fun writeLine(text: String) {
                write("$text\n")
            }

            override fun replaceLiveLines(lines: List<String>) {
                transcript.replaceLiveLines(lines)
            }

            override fun replaceLiveLinePrefixes(
                lines: List<String>,
                prefixes: List<String>,
            ) {
                transcript.replaceLiveLines(lines)
            }

            override fun flush() {
                if (manualScrollActive) {
                    terminal.flush()
                } else {
                    renderFollowingOutput()
                }
            }
        }

    fun updateLayout(layout: TerminalLayout) {
        followLayout = layout
    }

    fun updateFollowGeometry(
        layout: TerminalLayout,
        menuRows: Int = 0,
        inputRows: Int = 1,
    ) {
        followLayout = layout
        followMenuRows = menuRows
        followInputRows = inputRows
    }

    fun setFollowCursorRenderer(renderer: (() -> Unit)?) {
        followCursorRenderer = renderer
    }

    /** Positive rows show older output; negative rows move back toward the latest output. */
    fun scrollBy(
        rows: Int,
        layout: TerminalLayout,
        menuRows: Int = 0,
        inputRows: Int = 1,
    ): Boolean {
        if (rows == 0) return false
        val renderedRows = transcript.renderedRows(contentWidth(layout))
        accountForNewRows(renderedRows.size)
        val fullHeight = layout.outputBottomRowFor(menuRows, inputRows)
        val maxOffset = (renderedRows.size - fullHeight).coerceAtLeast(0)
        val nextOffset = (scrollOffsetRows + rows).coerceIn(0, maxOffset)
        val nextManualScrollActive =
            when {
                rows > 0 -> true
                rows < 0 && nextOffset == 0 -> false
                else -> manualScrollActive
            }
        if (nextOffset == scrollOffsetRows && nextManualScrollActive == manualScrollActive) return false

        scrollOffsetRows = nextOffset
        manualScrollActive = nextManualScrollActive
        renderRows(renderedRows, layout, menuRows, inputRows)
        terminal.flush()
        return true
    }

    /** Returns to automatic following before a submitted command starts producing output. */
    fun scrollToBottom(layout: TerminalLayout) {
        updateFollowGeometry(layout)
        scrollOffsetRows = 0
        manualScrollActive = false
        val renderedRows = transcript.renderedRows(contentWidth(layout))
        knownRenderedRowCount = renderedRows.size
        renderRows(renderedRows, layout, menuRows = 0, inputRows = 1)
    }

    fun redraw(
        layout: TerminalLayout,
        menuRows: Int = 0,
        inputRows: Int = 1,
    ) {
        updateFollowGeometry(layout, menuRows, inputRows)
        val height = layout.outputBottomRowFor(menuRows, inputRows)
        val renderedRows = transcript.renderedRows(contentWidth(layout))
        accountForNewRows(renderedRows.size)
        val maxOffset = (renderedRows.size - height).coerceAtLeast(0)
        scrollOffsetRows = scrollOffsetRows.coerceAtMost(maxOffset)
        renderRows(renderedRows, layout, menuRows, inputRows)
    }

    private fun renderFollowingOutput() {
        val layout = followLayout
        val fullHeight = layout.outputBottomRowFor(followMenuRows, followInputRows)
        val contentHeight = layout.followOutputBottomRowFor(followMenuRows, followInputRows)
        val renderedRows = transcript.renderedRows(contentWidth(layout), maxRows = contentHeight)
        terminal.inFrame(forceFlush = true) {
            terminal.write(Ansi.HIDE_CURSOR)
            renderVisibleRows(renderedRows, fullHeight, contentHeight, saveOutputCursor = true)
            followCursorRenderer?.invoke() ?: terminal.write(Ansi.RESTORE_CURSOR)
            terminal.write(Ansi.SHOW_CURSOR)
        }
    }

    private fun renderRows(
        renderedRows: List<String>,
        layout: TerminalLayout,
        menuRows: Int,
        inputRows: Int,
    ) {
        val fullHeight = layout.outputBottomRowFor(menuRows, inputRows)
        val contentHeight =
            if (manualScrollActive) {
                fullHeight
            } else {
                layout.followOutputBottomRowFor(menuRows, inputRows)
            }
        val bottomExclusive = (renderedRows.size - scrollOffsetRows).coerceAtLeast(0)
        val top = (bottomExclusive - contentHeight).coerceAtLeast(0)

        val visibleRows = renderedRows.subList(top, bottomExclusive)
        renderVisibleRows(
            renderedRows = visibleRows,
            fullHeight = fullHeight,
            contentHeight = contentHeight,
            saveOutputCursor = !manualScrollActive,
        )
    }

    private fun renderVisibleRows(
        renderedRows: List<String>,
        fullHeight: Int,
        contentHeight: Int,
        saveOutputCursor: Boolean,
    ) {
        terminal.write(Ansi.RESET)
        for (terminalRow in 1..fullHeight) {
            val visibleIndex = terminalRow - 1
            terminal.write("${Ansi.cursor(terminalRow, 1)}${Ansi.CLEAR_LINE}")
            if (terminalRow <= contentHeight && visibleIndex in renderedRows.indices) {
                terminal.write(renderedRows[visibleIndex])
                if (saveOutputCursor && visibleIndex == renderedRows.lastIndex) {
                    terminal.write(Ansi.SAVE_CURSOR)
                }
            }
        }
        terminal.write(Ansi.RESET)
    }

    private fun contentWidth(layout: TerminalLayout): Int = (layout.columns - 1).coerceAtLeast(1)

    private fun accountForNewRows(currentCount: Int) {
        if (manualScrollActive && knownRenderedRowCount > 0 && currentCount > knownRenderedRowCount) {
            scrollOffsetRows += currentCount - knownRenderedRowCount
        }
        knownRenderedRowCount = currentCount
    }
}

private class TerminalTranscript {
    private val completedLines = mutableListOf<String>()
    private val currentLine = StringBuilder()
    private var liveLines = emptyList<String>()
    private var cachedWidth = -1
    private var cachedCompletedLineCount = 0
    private val cachedCompletedRows = mutableListOf<String>()
    private var cachedTrailingStyle = ""

    fun append(text: String) {
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '\n' -> completeLine()
                '\r' -> if (index + 1 >= text.length || text[index + 1] != '\n') currentLine.clear()
                else -> currentLine.append(text[index])
            }
            index += 1
        }
    }

    fun renderedRows(
        width: Int,
        maxRows: Int? = null,
    ): List<String> {
        val safeWidth = width.coerceAtLeast(1)
        updateCompletedRowsCache(safeWidth)
        val rows =
            if (maxRows == null) {
                cachedCompletedRows.toMutableList()
            } else {
                cachedCompletedRows.takeLast(maxRows).toMutableList()
            }
        var activeStyle = cachedTrailingStyle
        val logicalLines = mutableListOf<String>()
        if (liveLines.isEmpty()) {
            logicalLines += currentLine.toString()
        } else {
            if (currentLine.isNotEmpty()) logicalLines += currentLine.toString()
            logicalLines += liveLines
            logicalLines += ""
        }
        for (line in logicalLines) {
            val wrapped = wrapStyledLine(line, safeWidth, activeStyle)
            rows += wrapped.rows
            activeStyle = wrapped.trailingStyle
        }
        return if (maxRows == null) rows else rows.takeLast(maxRows)
    }

    fun replaceLiveLines(lines: List<String>) {
        liveLines = lines.toList()
    }

    private fun completeLine() {
        completedLines += currentLine.toString()
        currentLine.clear()
        if (completedLines.size > MAX_TRANSCRIPT_LINES) {
            completedLines.removeAt(0)
            invalidateCompletedRowsCache()
        }
    }

    private fun updateCompletedRowsCache(width: Int) {
        if (cachedWidth != width || cachedCompletedLineCount > completedLines.size) {
            invalidateCompletedRowsCache()
            cachedWidth = width
        }
        while (cachedCompletedLineCount < completedLines.size) {
            val wrapped =
                wrapStyledLine(
                    line = completedLines[cachedCompletedLineCount],
                    width = width,
                    initialStyle = cachedTrailingStyle,
                )
            cachedCompletedRows += wrapped.rows
            cachedTrailingStyle = wrapped.trailingStyle
            cachedCompletedLineCount += 1
        }
    }

    private fun invalidateCompletedRowsCache() {
        cachedWidth = -1
        cachedCompletedLineCount = 0
        cachedCompletedRows.clear()
        cachedTrailingStyle = ""
    }

    private fun wrapStyledLine(
        line: String,
        width: Int,
        initialStyle: String,
    ): WrappedLine {
        val rows = mutableListOf<String>()
        var activeStyle = initialStyle
        var current = StringBuilder(activeStyle)
        var visibleWidth = 0
        var index = 0

        fun finishRow() {
            current.append(Ansi.RESET)
            rows += current.toString()
            current = StringBuilder(activeStyle)
            visibleWidth = 0
        }

        while (index < line.length) {
            if (line[index] == ESC && index + 1 < line.length && line[index + 1] == '[') {
                val end = ansiSequenceEnd(line, index)
                val sequence = line.substring(index, end)
                current.append(sequence)
                activeStyle = updatedStyle(activeStyle, sequence)
                index = end
                continue
            }

            val charCount =
                if (line[index].isHighSurrogate() &&
                    index + 1 < line.length &&
                    line[index + 1].isLowSurrogate()
                ) {
                    2
                } else {
                    1
                }
            val character = line.substring(index, index + charCount)
            val characterWidth = TextUtil.visibleWidth(character)
            if (characterWidth > 0 && visibleWidth > 0 && visibleWidth + characterWidth > width) finishRow()
            current.append(character)
            visibleWidth += characterWidth
            index += charCount
        }

        finishRow()
        return WrappedLine(rows, activeStyle)
    }

    private fun ansiSequenceEnd(
        text: String,
        start: Int,
    ): Int {
        var index = start + 2
        while (index < text.length && text[index] !in '@'..'~') index += 1
        return (index + 1).coerceAtMost(text.length)
    }

    private fun updatedStyle(
        current: String,
        sequence: String,
    ): String {
        if (!sequence.endsWith('m')) return current
        val parameters = sequence.substringAfter('[').dropLast(1)
        return if (parameters.isEmpty() || parameters == "0") "" else current + sequence
    }

    private data class WrappedLine(
        val rows: List<String>,
        val trailingStyle: String,
    )

    private companion object {
        val ESC: Char = Char(27)
        const val MAX_TRANSCRIPT_LINES = 20_000
    }
}

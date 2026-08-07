package org.koaks.agent.app

import org.koaks.agent.tui.Ansi
import org.koaks.agent.tui.LiveLinesOutput
import org.koaks.agent.tui.Output
import org.koaks.agent.tui.TerminalLayout
import org.koaks.agent.tui.TextUtil
import org.koaks.agent.tui.redrawLiveLinePrefixes
import org.koaks.agent.tui.redrawLiveLines

/**
 * Records application output so the alternate-screen layout can provide its own
 * scrollback while leaving the input rows pinned at the bottom of the terminal.
 */
internal class FixedTerminalViewport(private val terminal: Output) {
    private val transcript = TerminalTranscript()
    private var scrollOffsetRows = 0
    private var manualScrollActive = false
    private var knownRenderedRowCount = 0

    val contentOutput: Output = object : LiveLinesOutput {
        private var liveLineCount = 0

        override fun write(text: String) {
            transcript.append(text)
            if (!manualScrollActive) terminal.write(text)
        }

        override fun writeLine(text: String) {
            write("$text\n")
        }

        override fun replaceLiveLines(lines: List<String>) {
            transcript.replaceLiveLines(lines)
            if (!manualScrollActive) redrawLiveLines(terminal, liveLineCount, lines)
            liveLineCount = lines.size
        }

        override fun replaceLiveLinePrefixes(lines: List<String>, prefixes: List<String>) {
            transcript.replaceLiveLines(lines)
            if (!manualScrollActive) redrawLiveLinePrefixes(terminal, liveLineCount, prefixes)
        }

        override fun flush() {
            terminal.flush()
        }
    }

    /** Positive rows scroll toward older output; negative rows return toward the latest output. */
    fun scrollBy(rows: Int, layout: TerminalLayout, menuRows: Int = 0, inputRows: Int = 1): Boolean {
        if (rows == 0) return false
        val renderedRows = transcript.renderedRows(contentWidth(layout))
        accountForNewRows(renderedRows.size)
        val fullHeight = layout.outputBottomRowFor(menuRows, inputRows)
        val maxOffset = (renderedRows.size - fullHeight).coerceAtLeast(0)
        val nextOffset = (scrollOffsetRows + rows).coerceIn(0, maxOffset)
        val nextManualScrollActive = when {
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

    /** Restores the newest output before the submitted command starts producing more content. */
    fun scrollToBottom(layout: TerminalLayout) {
        scrollOffsetRows = 0
        manualScrollActive = false
        val renderedRows = transcript.renderedRows(contentWidth(layout))
        knownRenderedRowCount = renderedRows.size
        renderRows(renderedRows, layout, menuRows = 0, inputRows = 1)
    }

    fun redraw(layout: TerminalLayout, menuRows: Int = 0, inputRows: Int = 1) {
        val height = layout.outputBottomRowFor(menuRows, inputRows)
        val renderedRows = transcript.renderedRows(contentWidth(layout))
        accountForNewRows(renderedRows.size)
        val maxOffset = (renderedRows.size - height).coerceAtLeast(0)
        scrollOffsetRows = scrollOffsetRows.coerceAtMost(maxOffset)
        renderRows(renderedRows, layout, menuRows, inputRows)
    }

    val isViewingHistory: Boolean
        get() = manualScrollActive

    private fun renderRows(
        renderedRows: List<String>,
        layout: TerminalLayout,
        menuRows: Int,
        inputRows: Int,
    ) {
        val fullHeight = layout.outputBottomRowFor(menuRows, inputRows)
        val contentHeight = if (manualScrollActive) {
            fullHeight
        } else {
            layout.followOutputBottomRowFor(menuRows, inputRows)
        }
        val bottomExclusive = (renderedRows.size - scrollOffsetRows).coerceAtLeast(0)
        val top = (bottomExclusive - contentHeight).coerceAtLeast(0)

        terminal.write(Ansi.RESET)
        for (terminalRow in 1..fullHeight) {
            val historyIndex = top + terminalRow - 1
            terminal.write("${Ansi.cursor(terminalRow, 1)}${Ansi.CLEAR_LINE}")
            if (terminalRow <= contentHeight && historyIndex < bottomExclusive) {
                terminal.write(renderedRows[historyIndex])
            }
            if (!manualScrollActive && historyIndex == renderedRows.lastIndex) {
                // Keep the output cursor synchronized with the transcript after a full redraw.
                terminal.write(Ansi.SAVE_CURSOR)
            }
        }
        terminal.write(Ansi.RESET)
    }

    private fun contentWidth(layout: TerminalLayout): Int =
        (layout.columns - 1).coerceAtLeast(1)

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

    fun append(text: String) {
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '\n' -> completeLine()
                '\r' -> {
                    if (index + 1 >= text.length || text[index + 1] != '\n') currentLine.clear()
                }
                else -> currentLine.append(text[index])
            }
            index += 1
        }
    }

    fun renderedRows(width: Int): List<String> {
        val safeWidth = width.coerceAtLeast(1)
        val rows = mutableListOf<String>()
        var activeStyle = ""
        val logicalLines = completedLines.toMutableList()
        if (liveLines.isEmpty()) {
            logicalLines += currentLine.toString()
        } else {
            if (currentLine.isNotEmpty()) logicalLines += currentLine.toString()
            logicalLines += liveLines
            // Live terminal lines end with a newline, so the saved output cursor sits here.
            logicalLines += ""
        }
        for (line in logicalLines) {
            val wrapped = wrapStyledLine(line, safeWidth, activeStyle)
            rows += wrapped.rows
            activeStyle = wrapped.trailingStyle
        }
        return rows
    }

    fun replaceLiveLines(lines: List<String>) {
        liveLines = lines.toList()
    }

    private fun completeLine() {
        completedLines += currentLine.toString()
        currentLine.clear()
        if (completedLines.size > MAX_TRANSCRIPT_LINES) {
            completedLines.removeAt(0)
        }
    }

    private fun wrapStyledLine(line: String, width: Int, initialStyle: String): WrappedLine {
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

            val charCount = if (
                line[index].isHighSurrogate() &&
                index + 1 < line.length &&
                line[index + 1].isLowSurrogate()
            ) 2 else 1
            val character = line.substring(index, index + charCount)
            val characterWidth = TextUtil.visibleWidth(character)
            if (characterWidth > 0 && visibleWidth > 0 && visibleWidth + characterWidth > width) {
                finishRow()
            }
            current.append(character)
            visibleWidth += characterWidth
            index += charCount
        }

        finishRow()
        return WrappedLine(rows, activeStyle)
    }

    private fun ansiSequenceEnd(text: String, start: Int): Int {
        var index = start + 2
        while (index < text.length && text[index] !in '@'..'~') index += 1
        return (index + 1).coerceAtMost(text.length)
    }

    private fun updatedStyle(current: String, sequence: String): String {
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

package org.koaks.cli.app

import org.koaks.cli.tui.Ansi
import org.koaks.cli.tui.LiveLinesOutput
import org.koaks.cli.tui.Output
import org.koaks.cli.tui.TerminalLayout
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixedTerminalViewportTest {
    @Test
    fun scrollsRecordedOutputWithoutDrawingOverPinnedInputRows() {
        val terminal = ViewportRecordingOutput()
        val viewport = FixedTerminalViewport(terminal)
        val layout = TerminalLayout.of(rows = 10, columns = 80, fixedInput = true)
        repeat(10) { index -> viewport.contentOutput.writeLine("line ${index + 1}") }
        val outputBeforeScroll = terminal.content.length

        assertTrue(viewport.scrollBy(4, layout))

        val redraw = terminal.content.substring(outputBeforeScroll)
        assertContains(redraw, "${Ansi.cursor(1, 1)}${Ansi.CLEAR_LINE}line 1")
        assertContains(redraw, "${Ansi.cursor(layout.outputBottomRow, 1)}${Ansi.CLEAR_LINE}line 7")
        assertFalse(redraw.contains(Ansi.cursor(layout.inputTopRow, 1)))
    }

    @Test
    fun scrollingBackToBottomRestoresLatestRecordedRows() {
        val terminal = ViewportRecordingOutput()
        val viewport = FixedTerminalViewport(terminal)
        val layout = TerminalLayout.of(rows = 10, columns = 80, fixedInput = true)
        repeat(10) { index -> viewport.contentOutput.writeLine("line ${index + 1}") }
        viewport.scrollBy(4, layout)
        val outputBeforeRestore = terminal.content.length

        viewport.scrollToBottom(layout)

        val redraw = terminal.content.substring(outputBeforeRestore)
        assertContains(redraw, "${Ansi.cursor(1, 1)}${Ansi.CLEAR_LINE}line 8")
        assertContains(
            redraw,
            "${Ansi.cursor(layout.followOutputBottomRow - 1, 1)}${Ansi.CLEAR_LINE}line 10",
        )
    }

    @Test
    fun onlyReservesOutputRowsWhileCommandMenuIsVisible() {
        val terminal = ViewportRecordingOutput()
        val viewport = FixedTerminalViewport(terminal)
        val layout = TerminalLayout.of(
            rows = 10,
            columns = 80,
            fixedInput = true,
            commandMenuRows = 3,
        )
        repeat(10) { index -> viewport.contentOutput.writeLine("line ${index + 1}") }
        val beforeMenu = terminal.content.length

        viewport.redraw(layout, menuRows = 3)

        val menuRedraw = terminal.content.substring(beforeMenu)
        assertContains(menuRedraw, "${Ansi.cursor(1, 1)}${Ansi.CLEAR_LINE}")
        assertFalse(menuRedraw.contains("line 10"))
        assertFalse(menuRedraw.contains(Ansi.cursor(layout.outputBottomRowForMenu(3) + 1, 1)))

        val beforeClose = terminal.content.length
        viewport.redraw(layout, menuRows = 0)
        val fullHeightRedraw = terminal.content.substring(beforeClose)
        assertContains(fullHeightRedraw, "${Ansi.cursor(1, 1)}${Ansi.CLEAR_LINE}line 8")
        assertContains(fullHeightRedraw, Ansi.cursor(layout.outputBottomRow, 1))
    }

    @Test
    fun keepsScrolledHistoryAnchoredWhenNewOutputArrives() {
        val terminal = ViewportRecordingOutput()
        val viewport = FixedTerminalViewport(terminal)
        val layout = TerminalLayout.of(rows = 10, columns = 80, fixedInput = true)
        repeat(10) { index -> viewport.contentOutput.writeLine("line ${index + 1}") }
        viewport.scrollToBottom(layout)
        viewport.scrollBy(3, layout)
        viewport.contentOutput.writeLine("line 11")
        val beforeRedraw = terminal.content.length

        viewport.redraw(layout)

        val redraw = terminal.content.substring(beforeRedraw)
        assertContains(redraw, "${Ansi.cursor(1, 1)}${Ansi.CLEAR_LINE}line 2")
    }

    @Test
    fun redrawUsesTheLatestLiveProgressFrame() {
        val terminal = ViewportRecordingOutput()
        val viewport = FixedTerminalViewport(terminal)
        val layout = TerminalLayout.of(rows = 10, columns = 80, fixedInput = true)
        val liveOutput = viewport.contentOutput as LiveLinesOutput

        liveOutput.replaceLiveLines(listOf("□■■■  ▸ Subagent  Inspect"))
        val beforePrefixUpdate = terminal.content.length
        liveOutput.replaceLiveLinePrefixes(
            lines = listOf("■□■■  ▸ Subagent  Inspect"),
            prefixes = listOf("■□■■"),
        )
        val prefixUpdate = terminal.content.substring(beforePrefixUpdate)

        assertContains(prefixUpdate, "■□■■")
        assertFalse(prefixUpdate.contains(Ansi.CLEAR_LINE))

        val beforeRedraw = terminal.content.length

        viewport.redraw(layout)

        val redraw = terminal.content.substring(beforeRedraw)
        assertContains(redraw, "■□■■  ▸ Subagent  Inspect")
        assertFalse(redraw.contains("□■■■  ▸ Subagent  Inspect"))
    }

    @Test
    fun automaticFollowKeepsSafetyAreaWhileManualScrollCanUseTheBottomRow() {
        val terminal = ViewportRecordingOutput()
        val viewport = FixedTerminalViewport(terminal)
        val layout = TerminalLayout.of(rows = 10, columns = 80, fixedInput = true)
        repeat(8) { index -> viewport.contentOutput.writeLine("line ${index + 1}") }
        val beforeFollow = terminal.content.length

        viewport.scrollToBottom(layout)

        val followRedraw = terminal.content.substring(beforeFollow)
        assertContains(
            followRedraw,
            "${Ansi.cursor(layout.followOutputBottomRow - 1, 1)}${Ansi.CLEAR_LINE}line 8",
        )
        assertFalse(
            followRedraw.contains(
                "${Ansi.cursor(layout.outputBottomRow, 1)}${Ansi.CLEAR_LINE}line 8",
            ),
        )

        val beforeManualScroll = terminal.content.length
        assertTrue(viewport.scrollBy(1, layout))

        val manualRedraw = terminal.content.substring(beforeManualScroll)
        assertContains(
            manualRedraw,
            "${Ansi.cursor(layout.outputBottomRow, 1)}${Ansi.CLEAR_LINE}line 8",
        )
    }

    @Test
    fun newOutputIsRecordedWithoutWritingOverManuallyScrolledHistory() {
        val terminal = ViewportRecordingOutput()
        val viewport = FixedTerminalViewport(terminal)
        val layout = TerminalLayout.of(rows = 10, columns = 80, fixedInput = true)
        repeat(10) { index -> viewport.contentOutput.writeLine("line ${index + 1}") }
        viewport.scrollToBottom(layout)
        viewport.scrollBy(2, layout)
        val beforeNewOutput = terminal.content.length

        viewport.contentOutput.writeLine("line 11")

        assertEquals("", terminal.content.substring(beforeNewOutput))

        viewport.redraw(layout)
        val beforeReturnToFollow = terminal.content.length
        viewport.scrollBy(-100, layout)
        val followRedraw = terminal.content.substring(beforeReturnToFollow)

        assertContains(followRedraw, "line 11")
        assertContains(followRedraw, Ansi.SAVE_CURSOR)
    }
}

private class ViewportRecordingOutput : Output {
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

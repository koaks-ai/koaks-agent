package org.koaks.agent.tui.render

import org.koaks.agent.tui.io.Output
import org.koaks.agent.tui.state.TerminalLayout
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class FixedTerminalViewportTest {
    @Test
    fun streamingFlushFollowsLatestOutputWithoutTouchingInputRows() {
        val output = ViewportOutput()
        val layout = TerminalLayout.of(rows = 12, columns = 40, fixedInput = true)
        val viewport = FixedTerminalViewport(output, layout)

        repeat(20) { index -> viewport.contentOutput.writeLine("line ${index + 1}") }
        viewport.contentOutput.flush()

        assertContains(
            output.content,
            Ansi.cursor(layout.followOutputBottomRow - 1, 1) + Ansi.CLEAR_LINE + "line 20",
        )
        assertFalse(output.content.contains(Ansi.cursor(layout.compactInputTopRow, 1)))
    }
}

private class ViewportOutput : Output {
    private val buffer = StringBuilder()
    val content: String
        get() = buffer.toString()

    override fun write(text: String) {
        buffer.append(text)
    }

    override fun writeLine(text: String) {
        buffer.append(text).append('\n')
    }

    override fun flush() = Unit
}

package org.koaks.agent.tui

import kotlin.test.Test
import kotlin.test.assertEquals

class OutputTest {
    @Test
    fun terminalFrameEmitsCursorOperationsAsOneWrite() {
        val delegate = FrameRecordingOutput()
        val output = FrameBufferedOutput(delegate)

        output.beginFrame()
        output.write(Ansi.HIDE_CURSOR)
        output.write(Ansi.RESTORE_CURSOR)
        output.write("delta")
        output.flush()
        output.write(Ansi.SAVE_CURSOR)
        output.write(Ansi.SHOW_CURSOR)

        assertEquals(emptyList(), delegate.writes)
        assertEquals(0, delegate.flushCount)

        output.endFrame()

        assertEquals(
            listOf(
                Ansi.HIDE_CURSOR +
                    Ansi.RESTORE_CURSOR +
                    "delta" +
                    Ansi.SAVE_CURSOR +
                    Ansi.SHOW_CURSOR,
            ),
            delegate.writes,
        )
        assertEquals(1, delegate.flushCount)
    }

    @Test
    fun frameDoesNotForceAFlushWhenStreamingThrottleDefersIt() {
        val delegate = FrameRecordingOutput()
        val output = FrameBufferedOutput(delegate)

        output.inFrame {
            output.write("small delta")
        }

        assertEquals(listOf("small delta"), delegate.writes)
        assertEquals(0, delegate.flushCount)
    }

    @Test
    fun forcedFrameFlushesAfterTheBufferedWrite() {
        val delegate = FrameRecordingOutput()
        val output = FrameBufferedOutput(delegate)

        output.inFrame(forceFlush = true) {
            output.write("redraw")
        }

        assertEquals(listOf("redraw"), delegate.writes)
        assertEquals(1, delegate.flushCount)
    }
}

private class FrameRecordingOutput : Output {
    val writes = mutableListOf<String>()
    var flushCount = 0

    override fun write(text: String) {
        writes += text
    }

    override fun writeLine(text: String) {
        writes += "$text\n"
    }

    override fun flush() {
        flushCount += 1
    }
}

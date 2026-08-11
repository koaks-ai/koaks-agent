@file:OptIn(ExperimentalForeignApi::class)

package org.koaks.agent.tui

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush
import platform.posix.stdout

/**
 * The sink every renderer writes to. Abstracting stdout behind an interface keeps the
 * `tui` views and [org.koaks.agent.app] free of direct `print`/`fflush` calls, and gives
 * tests a place to capture output.
 */
internal interface Output {
    /** Writes [text] without a trailing newline. */
    fun write(text: String)

    /** Writes [text] followed by a newline. */
    fun writeLine(text: String = "")

    /** Flushes any buffered bytes to the terminal. */
    fun flush()
}

/** An output sink that can replace a small live region at the current output position. */
internal interface LiveLinesOutput : Output {
    fun replaceLiveLines(lines: List<String>)

    /** Updates equal-width prefixes in place while preserving the rest of every live line. */
    fun replaceLiveLinePrefixes(
        lines: List<String>,
        prefixes: List<String>,
    )
}

/** An output sink that can emit several cursor operations as one terminal update. */
internal interface FrameOutput : Output {
    fun beginFrame()

    fun endFrame(forceFlush: Boolean = false)
}

/**
 * Buffers writes made inside [inFrame] so terminals do not paint intermediate cursor
 * positions while a fixed-layout update is still being assembled.
 */
internal class FrameBufferedOutput(
    private val delegate: Output,
) : LiveLinesOutput,
    FrameOutput {
    private val frameBuffer = StringBuilder()
    private var frameDepth = 0
    private var flushRequested = false
    private var liveLineCount = 0

    override fun write(text: String) {
        if (frameDepth > 0) frameBuffer.append(text) else delegate.write(text)
    }

    override fun writeLine(text: String) {
        if (frameDepth > 0) {
            frameBuffer.append(text).append('\n')
        } else {
            delegate.writeLine(text)
        }
    }

    override fun replaceLiveLines(lines: List<String>) {
        redrawLiveLines(this, liveLineCount, lines)
        liveLineCount = lines.size
    }

    override fun replaceLiveLinePrefixes(
        lines: List<String>,
        prefixes: List<String>,
    ) {
        redrawLiveLinePrefixes(this, liveLineCount, prefixes)
    }

    override fun flush() {
        if (frameDepth > 0) {
            flushRequested = true
        } else {
            delegate.flush()
        }
    }

    override fun beginFrame() {
        if (frameDepth == 0) flushRequested = false
        frameDepth += 1
    }

    override fun endFrame(forceFlush: Boolean) {
        check(frameDepth > 0) { "No terminal frame is active." }
        if (forceFlush) flushRequested = true
        frameDepth -= 1
        if (frameDepth > 0) return

        if (frameBuffer.isNotEmpty()) {
            val rendered = frameBuffer.toString()
            frameBuffer.clear()
            delegate.write(rendered)
        }
        if (flushRequested) delegate.flush()
        flushRequested = false
    }
}

internal fun Output.withFrameBuffer(): Output = if (this is FrameOutput) this else FrameBufferedOutput(this)

internal inline fun <T> Output.inFrame(
    forceFlush: Boolean = false,
    block: () -> T,
): T {
    val framed = this as? FrameOutput
    if (framed == null) {
        return try {
            block()
        } finally {
            if (forceFlush) flush()
        }
    }

    framed.beginFrame()
    return try {
        block()
    } finally {
        framed.endFrame(forceFlush)
    }
}

/** The real terminal sink: `print` + libc `fflush`. */
internal class StdoutOutput : LiveLinesOutput {
    private var liveLineCount = 0

    override fun write(text: String) = print(text)

    override fun writeLine(text: String) = println(text)

    override fun replaceLiveLines(lines: List<String>) {
        redrawLiveLines(this, liveLineCount, lines)
        liveLineCount = lines.size
    }

    override fun replaceLiveLinePrefixes(
        lines: List<String>,
        prefixes: List<String>,
    ) {
        redrawLiveLinePrefixes(this, liveLineCount, prefixes)
    }

    override fun flush() {
        fflush(stdout)
    }
}

/** Replaces terminal lines while leaving the cursor directly after the new live region. */
internal fun redrawLiveLines(
    output: Output,
    previousLineCount: Int,
    lines: List<String>,
) {
    if (previousLineCount > 0) {
        output.write(Ansi.cursorUp(previousLineCount))
        repeat(previousLineCount) { index ->
            output.write("\r${Ansi.CLEAR_LINE}")
            if (index < previousLineCount - 1) output.write(Ansi.cursorDown(1))
        }
        if (previousLineCount > 1) output.write(Ansi.cursorUp(previousLineCount - 1))
    }

    lines.forEach { line ->
        output.write("\r${Ansi.CLEAR_LINE}$line\n")
    }
}

/** Rewrites only the prefix cells of existing live lines, without clearing their contents. */
internal fun redrawLiveLinePrefixes(
    output: Output,
    lineCount: Int,
    prefixes: List<String>,
) {
    if (lineCount <= 0 || prefixes.size != lineCount) return

    output.write(Ansi.cursorUp(lineCount))
    prefixes.forEach { prefix ->
        output.write("\r$prefix${Ansi.cursorDown(1)}")
    }
    output.write("\r")
}

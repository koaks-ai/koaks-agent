package org.koaks.agent.tui.input

import org.jline.terminal.Attributes
import org.jline.utils.NonBlockingReader
import org.koaks.agent.tui.platform.jvmTerminal

private val jlineReader: NonBlockingReader?
    get() = jvmTerminal?.reader()

private var originalAttributes: Attributes? = null

internal actual object PlatformTerminalInput {
    actual fun enterRawMode(): Boolean =
        runCatching {
            val terminal = jvmTerminal ?: return@runCatching false
            originalAttributes = terminal.enterRawMode()
            true
        }.getOrDefault(false)

    actual fun leaveRawMode() {
        val terminal = jvmTerminal ?: return
        val attributes = originalAttributes ?: return
        runCatching { terminal.attributes = attributes }
        originalAttributes = null
    }

    actual fun readKey(): TerminalKey {
        val reader = jlineReader ?: return System.`in`.read().toBasicTerminalKey()
        while (true) {
            reader.read().toTerminalKey(reader)?.let { return it }
        }
    }
}

private fun Int.toBasicTerminalKey(): TerminalKey =
    when (this) {
        -1, 3, 4 -> TerminalKey.EndOfInput
        8, 127 -> TerminalKey.Backspace
        9 -> TerminalKey.Tab
        10, 13 -> TerminalKey.Enter
        27 -> TerminalKey.Escape
        else -> TerminalKey.Text(toChar().toString())
    }

private fun Int.toTerminalKey(reader: NonBlockingReader? = null): TerminalKey? =
    when (this) {
        -1, 3, 4 -> TerminalKey.EndOfInput
        8, 127 -> TerminalKey.Backspace
        9 -> TerminalKey.Tab
        10, 13 -> TerminalKey.Enter
        27 -> reader?.readEscapeSequence() ?: TerminalKey.Escape
        else -> TerminalKey.Text(toChar().toString())
    }

private fun NonBlockingReader.readEscapeSequence(): TerminalKey? {
    val prefix = read()
    if (prefix == 'O'.code) return readSs3Sequence()
    if (prefix != '['.code) return TerminalKey.Escape

    val sequence = StringBuilder()
    val first = read()
    if (first < 0) return TerminalKey.EndOfInput
    val firstCharacter = first.toChar()
    sequence.append(firstCharacter)
    if (firstCharacter in '@'..'~') return decodeJvmCsiSequence(sequence.toString())
    while (true) {
        val value = read()
        if (value < 0) return TerminalKey.EndOfInput
        val character = value.toChar()
        sequence.append(character)
        if (character in '@'..'~') break
    }
    return decodeJvmCsiSequence(sequence.toString())
}

private fun NonBlockingReader.readSs3Sequence(): TerminalKey =
    when (val value = read()) {
        -1 -> TerminalKey.EndOfInput
        'A'.code -> TerminalKey.Up
        'B'.code -> TerminalKey.Down
        'C'.code -> TerminalKey.Right
        'D'.code -> TerminalKey.Left
        'H'.code -> TerminalKey.Home
        'F'.code -> TerminalKey.End
        else -> TerminalKey.Escape
    }

internal fun decodeJvmCsiSequence(sequence: String): TerminalKey =
    when (sequence.lastOrNull()) {
        'A' -> TerminalKey.Up
        'B' -> TerminalKey.Down
        'C' -> TerminalKey.Right
        'D' -> TerminalKey.Left
        'H' -> TerminalKey.Home
        'F' -> TerminalKey.End
        '~' ->
            when (sequence.substringBeforeLast('~')) {
                "3" -> TerminalKey.Delete
                "5" -> TerminalKey.PageUp
                "6" -> TerminalKey.PageDown
                else -> decodeCsiEnterKey(sequence) ?: TerminalKey.Escape
            }
        'u' -> decodeCsiEnterKey(sequence) ?: TerminalKey.Escape
        else -> TerminalKey.Escape
    }

@file:OptIn(ExperimentalForeignApi::class)

package org.koaks.agent.tui.input

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix._getwch
import platform.windows.GetAsyncKeyState
import platform.windows.GetConsoleMode
import platform.windows.GetStdHandle
import platform.windows.STD_INPUT_HANDLE

internal actual object NativeTerminalInput {
    actual fun enterRawMode(): Boolean =
        memScoped {
            val mode = alloc<UIntVar>()
            GetConsoleMode(GetStdHandle(STD_INPUT_HANDLE), mode.ptr) != 0
        }

    actual fun leaveRawMode() = Unit

    actual fun readKey(): TerminalKey {
        val value = _getwch().toInt()
        if (value == 0 || value == 0xE0) return readExtendedKey()
        return when (value) {
            3 -> TerminalKey.EndOfInput
            8 -> TerminalKey.Backspace
            9 -> TerminalKey.Tab
            10 -> TerminalKey.LineBreak
            13 -> if (shiftPressed()) TerminalKey.LineBreak else TerminalKey.Enter
            26 -> TerminalKey.EndOfInput
            27 -> readEscapeSequence()
            else -> TerminalKey.Text(value.toChar().toString())
        }
    }

    private fun readEscapeSequence(): TerminalKey {
        if (_getwch().toInt() != '['.code) return TerminalKey.Escape
        val sequence = readCsiSequence() ?: return TerminalKey.Escape
        return when (sequence) {
            "A" -> TerminalKey.Up
            "B" -> TerminalKey.Down
            "C" -> TerminalKey.Right
            "D" -> TerminalKey.Left
            "H" -> TerminalKey.Home
            "F" -> TerminalKey.End
            "3~" -> TerminalKey.Delete
            "5~" -> TerminalKey.PageUp
            "6~" -> TerminalKey.PageDown
            "200~" -> TerminalKey.Paste(readBracketedPaste())
            else -> decodeCsiEnterKey(sequence) ?: TerminalKey.Escape
        }
    }

    private fun shiftPressed(): Boolean = (GetAsyncKeyState(VIRTUAL_KEY_SHIFT).toInt() and KEY_PRESSED_MASK) != 0

    private fun readCsiSequence(): String? {
        val sequence = StringBuilder()
        while (true) {
            val value = _getwch().toInt()
            if (value < 0) return null
            val character = value.toChar()
            sequence.append(character)
            if (character in '@'..'~') return sequence.toString()
        }
    }

    /** Reads the payload between bracketed-paste start and end markers. */
    private fun readBracketedPaste(): String {
        val endMarker = "${Char(27)}[201~"
        val content = StringBuilder()
        var candidate = ""
        while (true) {
            val value = _getwch().toInt()
            if (value < 0) {
                content.append(candidate)
                return content.toString()
            }

            candidate += value.toChar()
            if (endMarker.startsWith(candidate)) {
                if (candidate == endMarker) return content.toString()
                continue
            }

            // Keep the longest suffix that could still be the end marker. This
            // preserves pasted ESC sequences that are not the bracketed-paste terminator.
            while (candidate.isNotEmpty() && !endMarker.startsWith(candidate)) {
                content.append(candidate[0])
                candidate = candidate.substring(1)
            }
        }
    }

    private fun readExtendedKey(): TerminalKey =
        when (_getwch().toInt()) {
            71 -> TerminalKey.Home
            72 -> TerminalKey.Up
            73 -> TerminalKey.PageUp
            75 -> TerminalKey.Left
            77 -> TerminalKey.Right
            79 -> TerminalKey.End
            80 -> TerminalKey.Down
            81 -> TerminalKey.PageDown
            83 -> TerminalKey.Delete
            else -> TerminalKey.Escape
        }
}

private const val VIRTUAL_KEY_SHIFT = 0x10
private const val KEY_PRESSED_MASK = 0x8000

package org.koaks.agent.tui

/**
 * Raw ANSI/VT100 escape sequences. Pure constants and builders — no I/O, no color
 * policy (that lives in [Theme], which decides whether to emit them at all).
 *
 * Sequences are built from the CSI code point (ESC, char 27, followed by an open
 * bracket) so the source file stays free of invisible control characters.
 */
internal object Ansi {
    /** Control Sequence Introducer: ESC (char 27) followed by an open bracket. */
    private val CSI: String = "${Char(27)}["

    val RESET = "${CSI}0m"
    val BOLD = "${CSI}1m"
    val DIM = "${CSI}2m"
    val BLUE = "${CSI}34m"
    val CYAN = "${CSI}36m"
    val GREEN = "${CSI}32m"
    val YELLOW = "${CSI}33m"
    val RED = "${CSI}31m"
    val ORANGE_YELLOW = "${CSI}38;5;214m"
    val CODE_TEXT = "${CSI}38;5;250m"
    val CODE_KEYWORD = "${CSI}38;5;81m"
    val CODE_STRING = "${CSI}38;5;114m"
    val CODE_COMMENT = "${CSI}38;5;244m"
    val CODE_NUMBER = "${CSI}38;5;215m"
    val CODE_LANGUAGE = "${CSI}38;5;117m"
    val DEFAULT_FOREGROUND = "${CSI}39m"
    val USER_INPUT_BORDER = CYAN
    val WELCOME_BORDER = "${CSI}38;2;196;157;255m"
    val USER_INPUT_BACKGROUND = "${CSI}48;2;28;28;42m"
    val USER_INPUT_BACKGROUND_FILL = "${CSI}38;2;28;28;42m"

    val CLEAR_SCREEN = "${CSI}2J"
    val CLEAR_LINE = "${CSI}2K"
    val HOME = "${CSI}H"
    val ENTER_ALTERNATE_SCREEN = "${CSI}?1049h"
    val LEAVE_ALTERNATE_SCREEN = "${CSI}?1049l"
    val DISABLE_MOUSE_TRACKING = "${CSI}?1000l${CSI}?1006l"
    val ENABLE_ALTERNATE_SCROLL = "${CSI}?1007h"
    val DISABLE_ALTERNATE_SCROLL = "${CSI}?1007l"
    val ENABLE_BRACKETED_PASTE = "${CSI}?2004h"
    val DISABLE_BRACKETED_PASTE = "${CSI}?2004l"
    val ENABLE_MODIFY_OTHER_KEYS = "${CSI}>4;1m"
    val DISABLE_MODIFY_OTHER_KEYS = "${CSI}>4;0m"
    val HIDE_CURSOR = "${CSI}?25l"
    val SHOW_CURSOR = "${CSI}?25h"
    val SAVE_CURSOR = "${CSI}s"
    val RESTORE_CURSOR = "${CSI}u"
    val RESET_SCROLL_REGION = "${CSI}r"
    val BLINKING_BAR_CURSOR = "${CSI}5 q"
    val RESET_CURSOR_STYLE = "${CSI}0 q"

    /** Moves the cursor to a 1-based [row], [column]. */
    fun cursor(row: Int, column: Int): String = "$CSI${row};${column}H"

    /** Moves the cursor up by [rows] without changing its column. */
    fun cursorUp(rows: Int): String = if (rows > 0) "$CSI${rows}A" else ""

    /** Moves the cursor down by [rows] without changing its column. */
    fun cursorDown(rows: Int): String = if (rows > 0) "$CSI${rows}B" else ""

    /** Moves the cursor to a 1-based column without changing its row. */
    fun cursorColumn(column: Int): String = "$CSI${column.coerceAtLeast(1)}G"

    /** Restricts scrolling to the inclusive row range [[top], [bottom]] (1-based). */
    fun scrollRegion(top: Int, bottom: Int): String = "$CSI${top};${bottom}r"
}

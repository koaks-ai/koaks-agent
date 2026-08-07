package org.koaks.cli.tui

/**
 * Semantic styling. Callers ask for meaning ("this is a label", "this is an error"),
 * not raw color codes, so the palette can change in one place. When [enabled] is false
 * (piped output, `NO_COLOR`, `TERM=dumb`) every helper returns its text unchanged.
 */
internal class Theme(val enabled: Boolean) {

    fun label(text: String): String = color(Ansi.BOLD + Ansi.GREEN, text)
    fun dim(text: String): String = color(Ansi.DIM, text)
    fun warn(text: String): String = color(Ansi.YELLOW, text)
    fun error(text: String): String = color(Ansi.RED, text)
    fun command(text: String): String = color(Ansi.BLUE, text)
    fun bold(text: String): String = color(Ansi.BOLD, text)
    fun inlineCode(text: String): String = color(Ansi.BLUE, text)
    fun codeBlockFrame(text: String): String = color(Ansi.DIM, text)
    fun codeBlockLanguage(text: String): String = color(Ansi.BOLD + Ansi.CODE_LANGUAGE, text)
    fun codeBlockText(text: String): String = color(Ansi.CODE_TEXT, text)
    fun codeKeyword(text: String): String = color(Ansi.BOLD + Ansi.CODE_KEYWORD, text)
    fun codeString(text: String): String = color(Ansi.CODE_STRING, text)
    fun codeComment(text: String): String = color(Ansi.CODE_COMMENT, text)
    fun codeNumber(text: String): String = color(Ansi.CODE_NUMBER, text)
    fun heading(level: Int, text: String): String = when (level) {
        1 -> color(Ansi.BOLD + Ansi.CYAN, text)
        2 -> color(Ansi.BOLD + Ansi.ORANGE_YELLOW, text)
        3 -> color(Ansi.GREEN, text)
        4 -> color(Ansi.BOLD, text)
        else -> text
    }

    fun inputSide(): String =
        if (enabled) "${Ansi.USER_INPUT_BORDER}┃${Ansi.RESET}" else "┃"
    fun inputPaddingSide(text: String): String =
        if (enabled) "${Ansi.USER_INPUT_BORDER}$text${Ansi.RESET}" else text
    fun inputBackground(text: String): String = color(Ansi.USER_INPUT_BACKGROUND, text)
    fun inputBackgroundFill(text: String): String = color(Ansi.USER_INPUT_BACKGROUND_FILL, text)
    fun inputCommand(text: String): String =
        if (enabled) "${Ansi.BLUE}$text${Ansi.DEFAULT_FOREGROUND}${Ansi.USER_INPUT_BACKGROUND}" else text
    fun commandMenuSelection(text: String): String = color(Ansi.BOLD + Ansi.BLUE, text)

    private fun color(code: String, text: String): String =
        if (enabled) "$code$text${Ansi.RESET}" else text
}

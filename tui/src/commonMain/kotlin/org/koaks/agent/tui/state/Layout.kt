package org.koaks.agent.tui.state

/** Maximum visible text rows inside the fixed input box. */
internal const val MAX_INPUT_TEXT_ROWS = 3

/** Rows reserved for the maximum-height input box, including its top and bottom edges. */
internal const val INPUT_BOX_HEIGHT = MAX_INPUT_TEXT_ROWS + 2

/** Extra breathing room kept below automatically-followed output. */
internal const val OUTPUT_BOTTOM_SAFETY_ROWS = 3

/** Fallback row count when the terminal size can't be probed. */
internal const val DEFAULT_TERM_ROWS = 30

/**
 * Pure geometry for the fixed-input layout: given the terminal [rows]/[columns] and
 * whether the pinned input box is active, exposes the 1-based row coordinates the
 * renderer draws against. Holds no theme and reads no environment — callers resolve
 * those and pass the numbers in via [of].
 */
internal class TerminalLayout private constructor(
    val fixedInput: Boolean,
    val rows: Int,
    val columns: Int,
    val commandMenuRows: Int,
) {
    val compactInputTopRow: Int = (rows - 2).coerceAtLeast(1)
    val compactInputRow: Int = (rows - 1).coerceAtLeast(1)
    val inputTopRow: Int = (rows - INPUT_BOX_HEIGHT + 1).coerceAtLeast(1)
    val menuTopRow: Int = (compactInputTopRow - commandMenuRows).coerceAtLeast(1)
    val outputBottomRow: Int = outputBottomRowFor(menuRows = 0, inputRows = 1)
    val followOutputBottomRow: Int =
        (outputBottomRow - OUTPUT_BOTTOM_SAFETY_ROWS).coerceAtLeast(1)
    val inputRow: Int = (rows - 1).coerceAtLeast(1)
    val inputBottomRow: Int = rows.coerceAtLeast(1)
    val reservedInputTopRow: Int = inputTopRow

    fun outputBottomRowForMenu(menuRows: Int): Int = outputBottomRowFor(menuRows, inputRows = 1)

    fun followOutputBottomRowForMenu(menuRows: Int): Int = followOutputBottomRowFor(menuRows, inputRows = 1)

    fun outputBottomRowFor(
        menuRows: Int,
        inputRows: Int,
    ): Int {
        val safeMenuRows = menuRows.coerceIn(0, commandMenuRows)
        val extraInputRows = inputRows.coerceIn(1, MAX_INPUT_TEXT_ROWS) - 1
        return (compactInputTopRow - extraInputRows - safeMenuRows - 1).coerceAtLeast(1)
    }

    fun followOutputBottomRowFor(
        menuRows: Int,
        inputRows: Int,
    ): Int = (outputBottomRowFor(menuRows, inputRows) - OUTPUT_BOTTOM_SAFETY_ROWS).coerceAtLeast(1)

    override fun equals(other: Any?): Boolean =
        other is TerminalLayout &&
            fixedInput == other.fixedInput &&
            rows == other.rows &&
            columns == other.columns &&
            commandMenuRows == other.commandMenuRows

    override fun hashCode(): Int {
        var result = fixedInput.hashCode()
        result = 31 * result + rows
        result = 31 * result + columns
        result = 31 * result + commandMenuRows
        return result
    }

    companion object {
        /** Builds a layout, clamping to the minimum usable rows/columns. */
        fun of(
            rows: Int,
            columns: Int,
            fixedInput: Boolean,
            commandMenuRows: Int = 0,
        ): TerminalLayout {
            val safeRows = rows.coerceAtLeast(INPUT_BOX_HEIGHT + 3)
            val availableMenuRows = (safeRows - INPUT_BOX_HEIGHT - 3).coerceAtLeast(0)
            return TerminalLayout(
                fixedInput = fixedInput,
                rows = safeRows,
                columns = columns.coerceAtLeast(32),
                commandMenuRows = commandMenuRows.coerceIn(0, availableMenuRows),
            )
        }
    }
}

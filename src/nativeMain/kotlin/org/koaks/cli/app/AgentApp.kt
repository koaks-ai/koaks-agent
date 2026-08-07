package org.koaks.cli.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.selectUnbiased
import org.koaks.cli.app.command.CommandRegistry
import org.koaks.cli.app.command.CommandResult
import org.koaks.cli.config.AgentConfig
import org.koaks.cli.config.CliException
import org.koaks.cli.config.Environment
import org.koaks.cli.config.PosixEnvironment
import org.koaks.cli.config.toBooleanFlagOrFalse
import org.koaks.cli.config.value
import org.koaks.cli.tui.Ansi
import org.koaks.cli.tui.DEFAULT_TERM_ROWS
import org.koaks.cli.tui.LineReader
import org.koaks.cli.tui.LineEditorSnapshot
import org.koaks.cli.tui.LineReadRequest
import org.koaks.cli.tui.LineSuggestion
import org.koaks.cli.tui.Output
import org.koaks.cli.tui.StdinLineReader
import org.koaks.cli.tui.StdoutOutput
import org.koaks.cli.tui.Terminal
import org.koaks.cli.tui.TerminalLayout
import org.koaks.cli.tui.Theme
import org.koaks.cli.tui.inFrame
import org.koaks.cli.tui.withFrameBuffer
import org.koaks.framework.loop.AgentEvent
import org.koaks.runtime.AgentRuntime
import kotlin.time.Duration.Companion.milliseconds

internal class AgentApp(
    initialConfig: AgentConfig,
    output: Output = StdoutOutput(),
    private val lineReader: LineReader = StdinLineReader,
    private val environment: Environment = PosixEnvironment,
    private val commands: CommandRegistry = CommandRegistry.builtins(),
) {
    private val output = output.withFrameBuffer()
    private val trace = CliTrace.open(environment)
    private val runtime = AgentRuntime {
        maxConcurrency = DEFAULT_MAX_CONCURRENCY
    }
    private val session = CliChatSession(initialConfig, runtime, trace.takeIf { it.enabled })

    suspend fun run() {
        val theme = Theme(ansiEnabled(environment))
        var layout = createLayout(environment, theme)
        val fixedViewport = if (layout.fixedInput) FixedTerminalViewport(output) else null
        val contentOutput = fixedViewport?.contentOutput ?: output
        var closedNormally = false

        if (layout.fixedInput) InputBox.enterFixedLayout(output, layout)
        try {
            WelcomeView.render(
                config = session.config,
                output = contentOutput,
                theme = theme,
                clearScreen = !layout.fixedInput,
                width = layout.columns - 1,
            )
            var hasCompletedTurn = false
            var prefetchedInput: PrefetchedInput? = null

            while (true) {
                val queued = prefetchedInput
                val input = if (queued != null) {
                    prefetchedInput = null
                    queued.value ?: break
                } else {
                    var lastEditorSnapshot: LineEditorSnapshot? = null
                    var staticMenuRows = 0
                    var fixedMenuRows = 0
                    var fixedInputRows = 1
                    layout = refreshLayout(layout, theme)
                    if (layout.fixedInput) {
                        InputBox.renderFixed(output, layout, theme)
                    } else {
                        InputBox.renderStaticStart(
                            output = output,
                            theme = theme,
                            commandMenuRows = if (theme.enabled) commands.suggestions.size else 0,
                        )
                    }
                    output.flush()

                    val entered = if (theme.enabled) {
                        lineReader.readLine(
                            LineReadRequest(
                                suggestions = lineSuggestions(),
                                commandNames = commands.commandNames,
                                scrollPageRows = (layout.outputBottomRow - 1).coerceAtLeast(1),
                                inputWidth = { InputBox.editorTextWidth(layout) },
                                onScroll = { rows ->
                                    layout = refreshLayout(layout, theme, fixedMenuRows, fixedInputRows)
                                    fixedViewport?.scrollBy(rows, layout, fixedMenuRows, fixedInputRows)
                                },
                                onInteractiveStart = {
                                    output.write(Ansi.ENABLE_BRACKETED_PASTE + Ansi.ENABLE_MODIFY_OTHER_KEYS)
                                    if (layout.fixedInput) {
                                        InputBox.enableInputScrolling(output)
                                    }
                                    output.flush()
                                },
                                onInteractiveEnd = {
                                    if (layout.fixedInput) {
                                        InputBox.disableInputScrolling(output)
                                    }
                                    output.write(Ansi.DISABLE_MODIFY_OTHER_KEYS + Ansi.DISABLE_BRACKETED_PASTE)
                                    output.flush()
                                },
                            ) { snapshot ->
                                lastEditorSnapshot = snapshot
                                if (layout.fixedInput) {
                                    output.inFrame(forceFlush = true) {
                                        output.write(Ansi.HIDE_CURSOR)
                                        try {
                                            val previousLayout = layout
                                            layout = refreshLayout(layout, theme, fixedMenuRows, fixedInputRows)
                                            val previousMenuRows = fixedMenuRows
                                            val previousInputRows = fixedInputRows
                                            fixedMenuRows = InputBox.renderFixedEditor(
                                                output = output,
                                                layout = layout,
                                                theme = theme,
                                                snapshot = snapshot,
                                                previousMenuRows = fixedMenuRows,
                                                previousInputRows = fixedInputRows,
                                            )
                                            fixedInputRows = InputBox.editorTextRows(snapshot, layout)
                                            if (
                                                layout != previousLayout ||
                                                fixedMenuRows != previousMenuRows ||
                                                fixedInputRows != previousInputRows
                                            ) {
                                                if (
                                                    fixedMenuRows != previousMenuRows ||
                                                    fixedInputRows != previousInputRows
                                                ) {
                                                    InputBox.updateFixedOutputRegion(
                                                        output,
                                                        layout,
                                                        fixedMenuRows,
                                                        fixedInputRows,
                                                    )
                                                }
                                                fixedViewport?.redraw(layout, fixedMenuRows, fixedInputRows)
                                                InputBox.positionFixedEditorCursor(
                                                    output = output,
                                                    layout = layout,
                                                    snapshot = snapshot,
                                                    menuRows = fixedMenuRows,
                                                )
                                            }
                                        } finally {
                                            output.write(Ansi.SHOW_CURSOR)
                                        }
                                    }
                                } else {
                                    layout = refreshLayout(layout, theme, fixedMenuRows)
                                    staticMenuRows = InputBox.renderStaticEditor(
                                        output = output,
                                        theme = theme,
                                        snapshot = snapshot,
                                        previousMenuRows = staticMenuRows,
                                    )
                                    output.flush()
                                }
                            }
                        )
                    } else {
                        lineReader.readLine()
                    } ?: break
                    layout = refreshLayout(layout, theme, fixedMenuRows, fixedInputRows)
                    if (layout.fixedInput) {
                        fixedViewport?.scrollToBottom(layout)
                        InputBox.restoreOutputCursor(output, layout, theme, fixedMenuRows, fixedInputRows)
                    } else {
                        val snapshot = lastEditorSnapshot
                        if (snapshot == null) {
                            InputBox.renderStaticEnd(output, theme, Terminal.stdinIsTty())
                        } else {
                            InputBox.renderStaticInteractiveEnd(output, theme, snapshot, staticMenuRows)
                        }
                    }
                    entered
                }

                val commandInput = input.trim()
                if (commandInput.isBlank()) continue

                val context = AgentContext(session, contentOutput, theme, layout)
                when (commands.dispatch(commandInput, context)) {
                    CommandResult.Exit -> break
                    CommandResult.Continue -> continue
                    null -> Unit
                }

                if (layout.fixedInput) {
                    if (hasCompletedTurn) contentOutput.writeLine()
                    InputBox.renderSubmittedMessage(contentOutput, theme, input, layout.columns - 1)
                }
                val events = try {
                    trace.turnStarted(input.length)
                    session.stream(input)
                } catch (e: CliException) {
                    contentOutput.writeLine(theme.error("[error] ${e.message}"))
                    continue
                }

                contentOutput.writeLine()
                contentOutput.flush()

                val eventPrinter = EventPrinter(session.config.showReasoning, contentOutput, theme, trace)
                if (layout.fixedInput && fixedViewport != null) {
                    val result = collectTurnAndReadNextInput(
                        events = events,
                        eventPrinter = eventPrinter,
                        initialLayout = layout,
                        viewport = fixedViewport,
                        theme = theme,
                    )
                    layout = result.layout
                    prefetchedInput = PrefetchedInput(result.input)
                } else {
                    collectTurnEvents(events, eventPrinter)
                }
                hasCompletedTurn = true
            }
            closedNormally = true
        } finally {
            session.close()
            runtime.close()
            trace.close()
            if (layout.fixedInput) InputBox.leaveFixedLayout(output, layout)
            if (closedNormally) {
                output.writeLine("\n${theme.dim("session closed")}")
            }
            output.flush()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun collectTurnEvents(
        events: Flow<AgentEvent>,
        eventPrinter: EventPrinter,
    ) = coroutineScope {
        val modelEvents = Channel<AgentEvent>(Channel.UNLIMITED)
        val modelJob = launch {
            try {
                events.collect { event -> modelEvents.send(event) }
                modelEvents.close()
            } catch (t: Throwable) {
                modelEvents.close(t)
            }
        }

        var modelOpen = true
        trace.collectorStarted()
        while (modelOpen) {
            selectUnbiased<Unit> {
                modelEvents.onReceiveCatching { result ->
                    val event = result.getOrNull()
                    if (event == null) {
                        val failure = result.exceptionOrNull()
                        if (failure == null) {
                            trace.collectorCompleted()
                        } else {
                            trace.collectorFailed(failure)
                            throw failure
                        }
                        modelOpen = false
                    } else {
                        trace.eventReceived(event)
                        eventPrinter.print(event)
                        trace.eventRendered(event)
                    }
                }
                if (eventPrinter.hasActiveProgressAnimation) {
                    onTimeout(SUBAGENT_ANIMATION_INTERVAL_MS.milliseconds) {
                        eventPrinter.advanceProgressAnimation()
                    }
                }
            }
        }
        modelJob.join()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun collectTurnAndReadNextInput(
        events: Flow<AgentEvent>,
        eventPrinter: EventPrinter,
        initialLayout: TerminalLayout,
        viewport: FixedTerminalViewport,
        theme: Theme,
    ): ConcurrentTurnResult = coroutineScope {
        var layout = initialLayout
        val inputEvents = Channel<ConcurrentInputEvent>(Channel.UNLIMITED)
        val inputDeferred = async(Dispatchers.Default) {
            try {
                lineReader.readLine(
                    LineReadRequest(
                        suggestions = lineSuggestions(),
                        commandNames = commands.commandNames,
                        scrollPageRows = (initialLayout.outputBottomRow - 1).coerceAtLeast(1),
                        inputWidth = { InputBox.editorTextWidth(layout) },
                        onScroll = { rows -> inputEvents.trySend(ConcurrentInputEvent.Scroll(rows)) },
                        onInteractiveStart = { inputEvents.trySend(ConcurrentInputEvent.Started) },
                        onInteractiveEnd = { inputEvents.trySend(ConcurrentInputEvent.Ended) },
                    ) { snapshot ->
                        inputEvents.trySend(ConcurrentInputEvent.Snapshot(snapshot))
                    }
                )
            } finally {
                inputEvents.close()
            }
        }
        val modelEvents = Channel<AgentEvent>(Channel.UNLIMITED)
        val modelJob = launch {
            try {
                events.collect { event -> modelEvents.send(event) }
                modelEvents.close()
            } catch (t: Throwable) {
                modelEvents.close(t)
            }
        }

        var fixedMenuRows = 0
        var fixedInputRows = 1
        var editorSnapshot: LineEditorSnapshot? = null
        var editorVisible = false
        var modelOpen = true
        var inputEventsOpen = true
        var inputReady = false
        var input: String? = null

        trace.collectorStarted()
        while (modelOpen || inputEventsOpen || !inputReady) {
            selectUnbiased<Unit> {
                if (modelOpen) {
                    modelEvents.onReceiveCatching { result ->
                        val event = result.getOrNull()
                        if (event == null) {
                            val failure = result.exceptionOrNull()
                            if (failure == null) {
                                trace.collectorCompleted()
                            } else {
                                trace.collectorFailed(failure)
                                throw failure
                            }
                            modelOpen = false
                        } else {
                            val previousLayout = layout
                            layout = refreshLayout(layout, theme, fixedMenuRows, fixedInputRows)
                            val outputSuppressed = viewport.isViewingHistory
                            val layoutChanged = layout != previousLayout
                            val renderVisibleFrame = editorVisible && !outputSuppressed
                            val redrawEditor = editorVisible && layoutChanged
                            if (renderVisibleFrame || redrawEditor) {
                                output.inFrame(forceFlush = layoutChanged) {
                                    output.write(Ansi.HIDE_CURSOR)
                                    try {
                                        if (renderVisibleFrame) InputBox.resumeFixedOutput(output)
                                        try {
                                            trace.eventReceived(event)
                                            eventPrinter.print(event)
                                            trace.eventRendered(event)
                                        } finally {
                                            if (renderVisibleFrame) InputBox.pauseFixedOutput(output)
                                        }
                                        if (redrawEditor) {
                                            val snapshot = editorSnapshot
                                            if (snapshot == null) {
                                                viewport.redraw(layout, fixedMenuRows, fixedInputRows)
                                            } else {
                                                val previousMenuRows = fixedMenuRows
                                                val previousInputRows = fixedInputRows
                                                fixedMenuRows = InputBox.renderFixedEditor(
                                                    output = output,
                                                    layout = layout,
                                                    theme = theme,
                                                    snapshot = snapshot,
                                                    previousMenuRows = fixedMenuRows,
                                                    previousInputRows = fixedInputRows,
                                                )
                                                fixedInputRows = InputBox.editorTextRows(snapshot, layout)
                                                if (
                                                    fixedMenuRows != previousMenuRows ||
                                                    fixedInputRows != previousInputRows
                                                ) {
                                                    InputBox.updateFixedOutputRegion(
                                                        output,
                                                        layout,
                                                        fixedMenuRows,
                                                        fixedInputRows,
                                                    )
                                                }
                                                viewport.redraw(layout, fixedMenuRows, fixedInputRows)
                                                InputBox.positionFixedEditorCursor(
                                                    output = output,
                                                    layout = layout,
                                                    snapshot = snapshot,
                                                    menuRows = fixedMenuRows,
                                                )
                                            }
                                        } else {
                                            editorSnapshot?.let { snapshot ->
                                                InputBox.positionFixedEditorCursor(
                                                    output = output,
                                                    layout = layout,
                                                    snapshot = snapshot,
                                                    menuRows = fixedMenuRows,
                                                )
                                            }
                                        }
                                    } finally {
                                        output.write(Ansi.SHOW_CURSOR)
                                    }
                                }
                            } else {
                                trace.eventReceived(event)
                                eventPrinter.print(event)
                                trace.eventRendered(event)
                            }
                        }
                    }
                }
                if (inputEventsOpen) {
                    inputEvents.onReceiveCatching { result ->
                        when (val event = result.getOrNull()) {
                            null -> inputEventsOpen = false
                            ConcurrentInputEvent.Started -> {
                                InputBox.enableInputScrolling(output)
                                output.write(Ansi.ENABLE_BRACKETED_PASTE + Ansi.ENABLE_MODIFY_OTHER_KEYS)
                                output.flush()
                            }
                            ConcurrentInputEvent.Ended -> {
                                InputBox.disableInputScrolling(output)
                                output.write(Ansi.DISABLE_MODIFY_OTHER_KEYS + Ansi.DISABLE_BRACKETED_PASTE)
                                output.flush()
                            }
                            is ConcurrentInputEvent.Scroll -> {
                                output.inFrame(forceFlush = true) {
                                    output.write(Ansi.HIDE_CURSOR)
                                    try {
                                        layout = refreshLayout(layout, theme, fixedMenuRows, fixedInputRows)
                                        viewport.scrollBy(event.rows, layout, fixedMenuRows, fixedInputRows)
                                        editorSnapshot?.let { snapshot ->
                                            fixedMenuRows = InputBox.renderFixedEditor(
                                                output = output,
                                                layout = layout,
                                                theme = theme,
                                                snapshot = snapshot,
                                                previousMenuRows = fixedMenuRows,
                                                previousInputRows = fixedInputRows,
                                            )
                                        }
                                    } finally {
                                        output.write(Ansi.SHOW_CURSOR)
                                    }
                                }
                            }
                            is ConcurrentInputEvent.Snapshot -> {
                                editorSnapshot = event.snapshot
                                output.inFrame(forceFlush = true) {
                                    output.write(Ansi.HIDE_CURSOR)
                                    try {
                                        val previousLayout = layout
                                        layout = refreshLayout(layout, theme, fixedMenuRows, fixedInputRows)
                                        if (!editorVisible) {
                                            InputBox.renderFixed(output, layout, theme)
                                            editorVisible = true
                                        }
                                        val previousMenuRows = fixedMenuRows
                                        val previousInputRows = fixedInputRows
                                        fixedMenuRows = InputBox.renderFixedEditor(
                                            output = output,
                                            layout = layout,
                                            theme = theme,
                                            snapshot = event.snapshot,
                                            previousMenuRows = fixedMenuRows,
                                            previousInputRows = fixedInputRows,
                                        )
                                        fixedInputRows = InputBox.editorTextRows(event.snapshot, layout)
                                        if (
                                            layout != previousLayout ||
                                            fixedMenuRows != previousMenuRows ||
                                            fixedInputRows != previousInputRows
                                        ) {
                                            if (
                                                fixedMenuRows != previousMenuRows ||
                                                fixedInputRows != previousInputRows
                                            ) {
                                                InputBox.updateFixedOutputRegion(
                                                    output,
                                                    layout,
                                                    fixedMenuRows,
                                                    fixedInputRows,
                                                )
                                            }
                                            viewport.redraw(layout, fixedMenuRows, fixedInputRows)
                                            InputBox.positionFixedEditorCursor(
                                                output = output,
                                                layout = layout,
                                                snapshot = event.snapshot,
                                                menuRows = fixedMenuRows,
                                            )
                                        }
                                    } finally {
                                        output.write(Ansi.SHOW_CURSOR)
                                    }
                                }
                            }
                        }
                    }
                }
                if (!inputReady) {
                    inputDeferred.onAwait { value ->
                        input = value
                        inputReady = true
                    }
                }
                if (eventPrinter.hasActiveProgressAnimation) {
                    onTimeout(SUBAGENT_ANIMATION_INTERVAL_MS.milliseconds) {
                        if (!viewport.isViewingHistory && fixedMenuRows == 0) {
                            layout = refreshLayout(layout, theme, fixedMenuRows, fixedInputRows)
                            if (editorVisible) {
                                output.inFrame(forceFlush = true) {
                                    output.write(Ansi.HIDE_CURSOR)
                                    try {
                                        InputBox.resumeFixedOutput(output)
                                        try {
                                            eventPrinter.advanceProgressAnimation(flush = false)
                                        } finally {
                                            InputBox.pauseFixedOutput(output)
                                        }
                                        editorSnapshot?.let { snapshot ->
                                            InputBox.positionFixedEditorCursor(
                                                output = output,
                                                layout = layout,
                                                snapshot = snapshot,
                                                menuRows = fixedMenuRows,
                                            )
                                        }
                                    } finally {
                                        output.write(Ansi.SHOW_CURSOR)
                                    }
                                }
                            } else {
                                eventPrinter.advanceProgressAnimation()
                            }
                        }
                    }
                }
            }
        }

        modelJob.join()
        InputBox.disableInputScrolling(output)
        if (editorVisible) {
            layout = refreshLayout(layout, theme, fixedMenuRows, fixedInputRows)
            viewport.scrollToBottom(layout)
            InputBox.restoreOutputCursor(output, layout, theme, fixedMenuRows, fixedInputRows)
        }
        output.flush()
        ConcurrentTurnResult(layout, input)
    }

    private fun lineSuggestions(): List<LineSuggestion> =
        commands.suggestions.map { suggestion ->
            LineSuggestion(suggestion.name, suggestion.description)
        }

    private fun refreshLayout(
        current: TerminalLayout,
        theme: Theme,
        fixedMenuRows: Int = 0,
        fixedInputRows: Int = 1,
    ): TerminalLayout {
        val next = createLayout(environment, theme)
        if (next == current) return current

        when {
            current.fixedInput && next.fixedInput -> {
                InputBox.resizeFixedLayout(output, current, next, fixedMenuRows, fixedInputRows)
            }
            current.fixedInput -> {
                InputBox.leaveFixedLayout(output, current)
            }
            next.fixedInput -> {
                InputBox.enterFixedLayout(output, next)
            }
        }
        return next
    }

    private fun createLayout(env: Environment, theme: Theme): TerminalLayout {
        val nativeSize = Terminal.size()
        val rows = (
            env.value("KOAKS_TERM_ROWS")?.toIntOrNull()
                ?: nativeSize?.rows
                ?: env.value("LINES")?.toIntOrNull()
                ?: DEFAULT_TERM_ROWS
            )
        val columns = (
            env.value("KOAKS_TERM_COLS")?.toIntOrNull()
                ?: nativeSize?.columns
                ?: env.value("COLUMNS")?.toIntOrNull()
                ?: PANEL_WIDTH
            )
        return TerminalLayout.of(
            rows = rows,
            columns = columns,
            fixedInput = theme.enabled && fixedInputEnabled(env),
            commandMenuRows = commands.suggestions.size,
        )
    }

    private fun ansiEnabled(env: Environment): Boolean {
        if (env.value("NO_COLOR") != null || env.value("KOAKS_NO_COLOR").toBooleanFlagOrFalse()) return false
        if (env.value("TERM") == "dumb") return false
        return true
    }

    private fun fixedInputEnabled(env: Environment): Boolean {
        when (env.value("KOAKS_FIXED_INPUT")?.lowercase()) {
            "0", "false", "no", "off" -> return false
            "1", "true", "yes", "on" -> return true
        }
        return true
    }

    private companion object {
        /** Caps parallel main + sub-agent instances (API rate / local resource friendly). */
        const val DEFAULT_MAX_CONCURRENCY = 8
        const val SUBAGENT_ANIMATION_INTERVAL_MS = 180L
    }

    private data class PrefetchedInput(val value: String?)

    private data class ConcurrentTurnResult(
        val layout: TerminalLayout,
        val input: String?,
    )

    private sealed interface ConcurrentInputEvent {
        object Started : ConcurrentInputEvent
        object Ended : ConcurrentInputEvent
        data class Scroll(val rows: Int) : ConcurrentInputEvent
        data class Snapshot(val snapshot: LineEditorSnapshot) : ConcurrentInputEvent
    }
}

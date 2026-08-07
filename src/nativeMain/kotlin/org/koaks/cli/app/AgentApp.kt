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
import org.koaks.framework.loop.AgentEvent
import org.koaks.runtime.AgentRuntime

internal class AgentApp(
    initialConfig: AgentConfig,
    private val output: Output = StdoutOutput(),
    private val lineReader: LineReader = StdinLineReader,
    private val environment: Environment = PosixEnvironment,
    private val commands: CommandRegistry = CommandRegistry.builtins(),
) {
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
            WelcomeView.render(session.config, contentOutput, theme, clearScreen = !layout.fixedInput)
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
                                onScroll = { rows ->
                                    layout = refreshLayout(layout, theme, fixedMenuRows)
                                    fixedViewport?.scrollBy(rows, layout, fixedMenuRows)
                                },
                                onInteractiveStart = {
                                    if (layout.fixedInput) {
                                        InputBox.enableInputScrolling(output)
                                        output.flush()
                                    }
                                },
                                onInteractiveEnd = {
                                    if (layout.fixedInput) {
                                        InputBox.disableInputScrolling(output)
                                        output.flush()
                                    }
                                },
                            ) { snapshot ->
                                lastEditorSnapshot = snapshot
                                val previousLayout = layout
                                layout = refreshLayout(layout, theme, fixedMenuRows)
                                if (layout.fixedInput) {
                                    val previousMenuRows = fixedMenuRows
                                    fixedMenuRows = InputBox.renderFixedEditor(
                                        output = output,
                                        layout = layout,
                                        theme = theme,
                                        snapshot = snapshot,
                                        previousMenuRows = fixedMenuRows,
                                    )
                                    if (layout != previousLayout || fixedMenuRows != previousMenuRows) {
                                        fixedViewport?.redraw(layout, fixedMenuRows)
                                        InputBox.positionFixedEditorCursor(
                                            output = output,
                                            layout = layout,
                                            snapshot = snapshot,
                                            menuRows = fixedMenuRows,
                                        )
                                    }
                                } else {
                                    staticMenuRows = InputBox.renderStaticEditor(
                                        output = output,
                                        theme = theme,
                                        snapshot = snapshot,
                                        previousMenuRows = staticMenuRows,
                                    )
                                }
                                output.flush()
                            }
                        )
                    } else {
                        lineReader.readLine()
                    }?.trimEnd() ?: break
                    layout = refreshLayout(layout, theme, fixedMenuRows)
                    if (layout.fixedInput) {
                        fixedViewport?.scrollToBottom(layout)
                        InputBox.restoreOutputCursor(output, layout, theme, fixedMenuRows)
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
                    onTimeout(SUBAGENT_ANIMATION_INTERVAL_MS) {
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
        val inputEvents = Channel<ConcurrentInputEvent>(Channel.UNLIMITED)
        val inputDeferred = async(Dispatchers.Default) {
            try {
                lineReader.readLine(
                    LineReadRequest(
                        suggestions = lineSuggestions(),
                        commandNames = commands.commandNames,
                        scrollPageRows = (initialLayout.outputBottomRow - 1).coerceAtLeast(1),
                        onScroll = { rows -> inputEvents.trySend(ConcurrentInputEvent.Scroll(rows)) },
                        onInteractiveStart = { inputEvents.trySend(ConcurrentInputEvent.Started) },
                        onInteractiveEnd = { inputEvents.trySend(ConcurrentInputEvent.Ended) },
                    ) { snapshot ->
                        inputEvents.trySend(ConcurrentInputEvent.Snapshot(snapshot))
                    }
                )?.trimEnd()
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

        var layout = initialLayout
        var fixedMenuRows = 0
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
                            layout = refreshLayout(layout, theme, fixedMenuRows)
                            val outputSuppressed = viewport.isViewingHistory
                            if (editorVisible && !outputSuppressed) InputBox.resumeFixedOutput(output)
                            trace.eventReceived(event)
                            eventPrinter.print(event)
                            trace.eventRendered(event)
                            val layoutChanged = layout != previousLayout
                            if (editorVisible && (!outputSuppressed || layoutChanged)) {
                                if (!outputSuppressed) InputBox.pauseFixedOutput(output)
                                viewport.redraw(layout, fixedMenuRows)
                                editorSnapshot?.let { snapshot ->
                                    fixedMenuRows = InputBox.renderFixedEditor(
                                        output = output,
                                        layout = layout,
                                        theme = theme,
                                        snapshot = snapshot,
                                        previousMenuRows = fixedMenuRows,
                                    )
                                }
                                output.flush()
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
                                output.flush()
                            }
                            ConcurrentInputEvent.Ended -> {
                                InputBox.disableInputScrolling(output)
                                output.flush()
                            }
                            is ConcurrentInputEvent.Scroll -> {
                                layout = refreshLayout(layout, theme, fixedMenuRows)
                                viewport.scrollBy(event.rows, layout, fixedMenuRows)
                                editorSnapshot?.let { snapshot ->
                                    fixedMenuRows = InputBox.renderFixedEditor(
                                        output = output,
                                        layout = layout,
                                        theme = theme,
                                        snapshot = snapshot,
                                        previousMenuRows = fixedMenuRows,
                                    )
                                }
                                output.flush()
                            }
                            is ConcurrentInputEvent.Snapshot -> {
                                editorSnapshot = event.snapshot
                                val previousLayout = layout
                                layout = refreshLayout(layout, theme, fixedMenuRows)
                                if (!editorVisible) {
                                    InputBox.renderFixed(output, layout, theme)
                                    editorVisible = true
                                }
                                val previousMenuRows = fixedMenuRows
                                fixedMenuRows = InputBox.renderFixedEditor(
                                    output = output,
                                    layout = layout,
                                    theme = theme,
                                    snapshot = event.snapshot,
                                    previousMenuRows = fixedMenuRows,
                                )
                                if (layout != previousLayout || fixedMenuRows != previousMenuRows) {
                                    viewport.redraw(layout, fixedMenuRows)
                                    InputBox.positionFixedEditorCursor(
                                        output = output,
                                        layout = layout,
                                        snapshot = event.snapshot,
                                        menuRows = fixedMenuRows,
                                    )
                                }
                                output.flush()
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
                    onTimeout(SUBAGENT_ANIMATION_INTERVAL_MS) {
                        if (!viewport.isViewingHistory && fixedMenuRows == 0) {
                            layout = refreshLayout(layout, theme, fixedMenuRows)
                            if (editorVisible) InputBox.resumeFixedOutput(output)
                            eventPrinter.advanceProgressAnimation(flush = !editorVisible)
                            if (editorVisible) {
                                InputBox.pauseFixedOutput(output)
                                editorSnapshot?.let { snapshot ->
                                    InputBox.positionFixedEditorCursor(
                                        output = output,
                                        layout = layout,
                                        snapshot = snapshot,
                                        menuRows = fixedMenuRows,
                                    )
                                }
                                output.flush()
                            }
                        }
                    }
                }
            }
        }

        modelJob.join()
        InputBox.disableInputScrolling(output)
        if (editorVisible) {
            layout = refreshLayout(layout, theme, fixedMenuRows)
            viewport.scrollToBottom(layout)
            InputBox.restoreOutputCursor(output, layout, theme, fixedMenuRows)
        }
        output.flush()
        ConcurrentTurnResult(layout, input)
    }

    private fun lineSuggestions(): List<LineSuggestion> =
        commands.suggestions.map { suggestion ->
            LineSuggestion(suggestion.name, suggestion.description)
        }

    private fun refreshLayout(current: TerminalLayout, theme: Theme, fixedMenuRows: Int = 0): TerminalLayout {
        val next = createLayout(environment, theme)
        if (next == current) return current

        when {
            current.fixedInput && next.fixedInput -> {
                InputBox.resizeFixedLayout(output, current, next, fixedMenuRows)
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

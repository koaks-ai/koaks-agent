package org.koaks.agent.tui.frontend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.selectUnbiased
import org.koaks.agent.definition.AgentSetupException
import org.koaks.agent.definition.SetupFailure
import org.koaks.agent.platform.Environment
import org.koaks.agent.platform.PlatformEnvironment
import org.koaks.agent.platform.value
import org.koaks.agent.session.ChatSession
import org.koaks.agent.session.SessionUpdateFailure
import org.koaks.agent.session.SessionUpdateResult
import org.koaks.agent.tui.approval.ApprovalDecision
import org.koaks.agent.tui.approval.TerminalToolApproval
import org.koaks.agent.tui.approval.ToolApprovalRequest
import org.koaks.agent.tui.command.CommandRegistry
import org.koaks.agent.tui.command.CommandResult
import org.koaks.agent.tui.command.MessageTone
import org.koaks.agent.tui.input.InputBox
import org.koaks.agent.tui.input.LineEditorSnapshot
import org.koaks.agent.tui.input.LineReadRequest
import org.koaks.agent.tui.input.LineReader
import org.koaks.agent.tui.input.LineSuggestion
import org.koaks.agent.tui.input.StdinLineReader
import org.koaks.agent.tui.input.TerminalKey
import org.koaks.agent.tui.io.Output
import org.koaks.agent.tui.io.StdoutOutput
import org.koaks.agent.tui.io.inFrame
import org.koaks.agent.tui.io.withFrameBuffer
import org.koaks.agent.tui.platform.Terminal
import org.koaks.agent.tui.render.Ansi
import org.koaks.agent.tui.render.EventPrinter
import org.koaks.agent.tui.render.FixedTerminalViewport
import org.koaks.agent.tui.render.Theme
import org.koaks.agent.tui.render.WelcomeView
import org.koaks.agent.tui.state.DEFAULT_TERM_ROWS
import org.koaks.agent.tui.state.PANEL_WIDTH
import org.koaks.agent.tui.state.Reduction
import org.koaks.agent.tui.state.TerminalLayout
import org.koaks.agent.tui.state.UiAction
import org.koaks.agent.tui.state.UiEffect
import org.koaks.agent.tui.state.UiReducer
import org.koaks.agent.tui.state.UiState
import org.koaks.agent.tui.trace.NoopTerminalTrace
import org.koaks.agent.tui.trace.TerminalTrace
import org.koaks.framework.loop.AgentEvent
import kotlin.time.Duration.Companion.milliseconds

/**
 * Terminal-only presentation loop. Runtime ownership and Agent replacement remain
 * behind [ChatSession] in the agent module.
 */
public class TerminalFrontend internal constructor(
    private val session: ChatSession,
    private val trace: TerminalTrace,
    output: Output,
    private val lineReader: LineReader,
    private val environment: Environment,
    private val commands: CommandRegistry,
    private val toolApproval: TerminalToolApproval = TerminalToolApproval(),
    private val setupFailureMessage: (SetupFailure) -> String = { it.toString() },
) {
    public constructor(
        session: ChatSession,
        trace: TerminalTrace = NoopTerminalTrace,
        environment: Environment = PlatformEnvironment,
        toolApproval: TerminalToolApproval = TerminalToolApproval(),
        setupFailureMessage: (SetupFailure) -> String,
    ) : this(
        session = session,
        trace = trace,
        output = StdoutOutput(),
        lineReader = StdinLineReader,
        environment = environment,
        commands = CommandRegistry.builtins(),
        toolApproval = toolApproval,
        setupFailureMessage = setupFailureMessage,
    )

    private val output = output.withFrameBuffer()
    private val reducer = UiReducer()
    private var state = UiState(session.snapshot)

    public suspend fun run() {
        val theme = Theme(ansiEnabled(environment))
        var layout = createLayout(theme)
        val fixedLayout = layout.fixedInput
        val viewport = if (fixedLayout) FixedTerminalViewport(output, layout) else null
        val contentOutput = viewport?.contentOutput ?: output
        if (fixedLayout) InputBox.enterFixedLayout(output, layout)

        try {
            WelcomeView.render(
                config = session.snapshot,
                output = contentOutput,
                theme = theme,
                clearScreen = theme.enabled && !fixedLayout,
                width = if (fixedLayout) layout.columns - 1 else PANEL_WIDTH,
            )
            contentOutput.flush()

            var prefetchedInput: InputResult? = null
            while (!state.exitRequested) {
                val inputResult = prefetchedInput ?: readInput(theme, layout, viewport)
                prefetchedInput = null
                layout = inputResult.layout
                val input = inputResult.input ?: break
                val commandInput = input.trim()
                if (commandInput.isEmpty()) continue

                when (val command = commands.dispatch(commandInput, session.snapshot)) {
                    CommandResult.Exit -> {
                        dispatch(UiAction.ExitRequested)
                        continue
                    }
                    is CommandResult.Continue -> {
                        command.message?.let { printCommandMessage(it.text, it.tone, theme, contentOutput) }
                        continue
                    }
                    is CommandResult.Update -> {
                        when (val result = session.update(command.command)) {
                            is SessionUpdateResult.Updated -> {
                                state = state.copy(snapshot = result.snapshot)
                                val message = command.message(result.snapshot)
                                printCommandMessage(message.text, message.tone, theme, contentOutput)
                            }
                            is SessionUpdateResult.Rejected ->
                                printCommandMessage(
                                    "[error] ${result.reason.message()}",
                                    MessageTone.ERROR,
                                    theme,
                                    contentOutput,
                                )
                        }
                        continue
                    }
                    null -> Unit
                }

                if (layout.fixedInput) {
                    InputBox.renderSubmittedMessage(contentOutput, theme, input, layout.columns - 1)
                    contentOutput.flush()
                }
                val start = dispatch(UiAction.InputSubmitted(input))
                val effect = start.effects.singleOrNull() as? UiEffect.RunAgent ?: continue
                val events =
                    try {
                        trace.turnStarted(effect.input.length)
                        session.stream(effect.input)
                    } catch (error: AgentSetupException) {
                        val message = setupFailureMessage(error.failure)
                        contentOutput.writeLine(theme.error("[error] $message"))
                        contentOutput.flush()
                        dispatch(UiAction.RunFailed(message))
                        continue
                    }

                if (!layout.fixedInput) contentOutput.writeLine()
                val printer = EventPrinter(session.snapshot.reasoningEnabled, contentOutput, theme, trace)
                if (layout.fixedInput && viewport != null) {
                    val result =
                        collectTurnAndReadNextInput(
                            events = events,
                            printer = printer,
                            theme = theme,
                            initialLayout = layout,
                            viewport = viewport,
                        )
                    layout = result.layout
                    prefetchedInput = InputResult(result.input, result.layout)
                } else {
                    collectTurn(events, printer, theme)
                }
            }
        } finally {
            if (fixedLayout) {
                InputBox.leaveFixedLayout(output, layout)
                output.flush()
            }
        }

        output.writeLine("\n${theme.dim("session closed")}")
        output.flush()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun collectTurn(
        events: Flow<AgentEvent>,
        printer: EventPrinter,
        theme: Theme,
    ): Unit =
        coroutineScope {
            val actions = Channel<UiAction>(EVENT_CHANNEL_CAPACITY)
            val collector =
                launch {
                    try {
                        events.collect { event -> actions.send(UiAction.AgentEventReceived(event)) }
                        actions.send(UiAction.RunCompleted)
                        actions.close()
                    } catch (failure: Throwable) {
                        actions.close(failure)
                    }
                }

            trace.collectorStarted()
            var open = true
            while (open) {
                select<Unit> {
                    actions.onReceiveCatching { result ->
                        val action = result.getOrNull()
                        if (action == null) {
                            val failure = result.exceptionOrNull()
                            if (failure == null) trace.collectorCompleted() else trace.collectorFailed(failure)
                            if (failure != null) throw failure
                            open = false
                        } else {
                            if (action is UiAction.AgentEventReceived) {
                                trace.eventReceived(action.event)
                                printer.print(action.event)
                                trace.eventRendered(action.event)
                            }
                            dispatch(action)
                        }
                    }
                    toolApproval.requests.onReceive { request ->
                        request.respond(readStaticApproval(request, theme))
                    }
                    if (printer.hasActiveProgressAnimation) {
                        onTimeout(SUBAGENT_ANIMATION_INTERVAL) { printer.advanceProgressAnimation() }
                    }
                }
            }
            collector.join()
        }

    private fun readStaticApproval(
        request: ToolApprovalRequest,
        theme: Theme,
    ): ApprovalDecision {
        val summary = approvalArgumentSummary(request.arguments)
        output.writeLine(theme.warn("\n[approval required] ${request.toolName} $summary"))
        output.writeLine("1. Allow once")
        output.writeLine("2. Allow for this session")
        output.writeLine("3. Deny")
        output.write("Select [1/2/3] (default 1): ")
        output.flush()
        return when (lineReader.readLine()?.trim()?.lowercase()) {
            null, "", "1", "y", "yes", "a", "allow" -> ApprovalDecision.AllowOnce
            "2", "s", "session" -> ApprovalDecision.AllowForSession
            else -> ApprovalDecision.Deny
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun collectTurnAndReadNextInput(
        events: Flow<AgentEvent>,
        printer: EventPrinter,
        theme: Theme,
        initialLayout: TerminalLayout,
        viewport: FixedTerminalViewport,
    ): ConcurrentTurnResult =
        coroutineScope {
            val actions = Channel<UiAction>(EVENT_CHANNEL_CAPACITY)
            val inputEvents = Channel<ConcurrentInputEvent>(EVENT_CHANNEL_CAPACITY)
            val frame = FixedInputFrame(initialLayout)

            InputBox.renderFixed(output, frame.layout, theme)
            output.flush()

            fun sendInputEvent(event: ConcurrentInputEvent) {
                runBlocking { inputEvents.send(event) }
            }

            val inputDeferred =
                async(Dispatchers.Default) {
                    try {
                        lineReader.readLine(
                            LineReadRequest(
                                suggestions = commands.suggestions.map { LineSuggestion(it.name, it.description) },
                                commandNames = commands.commandNames,
                                scrollPageRows = (frame.layout.outputBottomRow - 1).coerceAtLeast(1),
                                inputWidth = { InputBox.editorTextWidth(frame.layout) },
                                onKey = { key ->
                                    runBlocking {
                                        val handled = CompletableDeferred<Boolean>()
                                        inputEvents.send(ConcurrentInputEvent.KeyPressed(key, handled))
                                        handled.await()
                                    }
                                },
                                onScroll = { rows -> sendInputEvent(ConcurrentInputEvent.Scroll(rows)) },
                                onInteractiveStart = { sendInputEvent(ConcurrentInputEvent.Started) },
                                onInteractiveEnd = { sendInputEvent(ConcurrentInputEvent.Ended) },
                                onUpdate = { snapshot ->
                                    sendInputEvent(ConcurrentInputEvent.Snapshot(snapshot))
                                },
                            ),
                        )
                    } finally {
                        inputEvents.close()
                    }
                }
            val collector =
                launch {
                    try {
                        events.collect { event -> actions.send(UiAction.AgentEventReceived(event)) }
                        actions.send(UiAction.RunCompleted)
                        actions.close()
                    } catch (failure: Throwable) {
                        actions.close(failure)
                    }
                }

            trace.collectorStarted()
            var modelOpen = true
            var inputEventsOpen = true
            var inputReady = false
            var inputEnded = false
            var input: String? = null
            var normalSnapshot: LineEditorSnapshot? = null
            var activeApproval: ApprovalMenuState? = null

            while (modelOpen || inputEventsOpen || !inputReady) {
                selectUnbiased<Unit> {
                    if (modelOpen) {
                        actions.onReceiveCatching { result ->
                            val action = result.getOrNull()
                            if (action == null) {
                                val failure = result.exceptionOrNull()
                                if (failure == null) trace.collectorCompleted() else trace.collectorFailed(failure)
                                if (failure != null) throw failure
                                modelOpen = false
                            } else {
                                if (action is UiAction.AgentEventReceived) {
                                    trace.eventReceived(action.event)
                                    printer.print(action.event)
                                    trace.eventRendered(action.event)
                                }
                                dispatch(action)
                            }
                        }
                    }
                    if (modelOpen) {
                        toolApproval.requests.onReceive { request ->
                            activeApproval = ApprovalMenuState(request)
                            renderFixedSnapshot(
                                theme,
                                viewport,
                                frame,
                                approvalSnapshot(checkNotNull(activeApproval)),
                            )
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
                                    finishFixedInput(theme, viewport, frame)
                                    inputEnded = true
                                }
                                is ConcurrentInputEvent.Scroll -> {
                                    scrollFixedViewport(theme, viewport, frame, event.rows)
                                }
                                is ConcurrentInputEvent.Snapshot -> {
                                    normalSnapshot = event.snapshot
                                    if (activeApproval == null) {
                                        renderFixedSnapshot(theme, viewport, frame, event.snapshot)
                                    }
                                }
                                is ConcurrentInputEvent.KeyPressed -> {
                                    val approval = activeApproval
                                    if (approval == null) {
                                        event.handled.complete(false)
                                    } else {
                                        when (val outcome = approval.handle(event.key)) {
                                            ApprovalKeyOutcome.Consumed -> event.handled.complete(true)
                                            ApprovalKeyOutcome.Updated -> {
                                                renderFixedSnapshot(
                                                    theme,
                                                    viewport,
                                                    frame,
                                                    approvalSnapshot(approval),
                                                )
                                                event.handled.complete(true)
                                            }
                                            is ApprovalKeyOutcome.Resolved -> {
                                                approval.request.respond(outcome.decision)
                                                activeApproval = null
                                                renderFixedSnapshot(
                                                    theme,
                                                    viewport,
                                                    frame,
                                                    normalSnapshot ?: emptyInputSnapshot(),
                                                )
                                                event.handled.complete(outcome.consumeKey)
                                            }
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
                    if (printer.hasActiveProgressAnimation) {
                        onTimeout(SUBAGENT_ANIMATION_INTERVAL) { printer.advanceProgressAnimation() }
                    }
                }
            }

            collector.join()
            if (!inputEnded) finishFixedInput(theme, viewport, frame)
            ConcurrentTurnResult(input, frame.layout)
        }

    private fun dispatch(action: UiAction): Reduction =
        reducer.reduce(state, action).also { reduction ->
            state = reduction.state
        }

    private fun readInput(
        theme: Theme,
        initialLayout: TerminalLayout,
        viewport: FixedTerminalViewport?,
    ): InputResult {
        if (!initialLayout.fixedInput) {
            val input =
                if (theme.enabled) {
                    readStaticInput(theme)
                } else {
                    output.write(theme.dim("\n> "))
                    output.flush()
                    lineReader.readLine()
                }
            return InputResult(input, initialLayout)
        }

        return readFixedInput(theme, initialLayout, checkNotNull(viewport))
    }

    private fun readFixedInput(
        theme: Theme,
        initialLayout: TerminalLayout,
        viewport: FixedTerminalViewport,
    ): InputResult {
        val frame = FixedInputFrame(initialLayout)
        InputBox.renderFixed(output, frame.layout, theme)
        output.flush()

        val input =
            lineReader.readLine(
                LineReadRequest(
                    suggestions = commands.suggestions.map { LineSuggestion(it.name, it.description) },
                    commandNames = commands.commandNames,
                    scrollPageRows = (frame.layout.outputBottomRow - 1).coerceAtLeast(1),
                    inputWidth = { InputBox.editorTextWidth(frame.layout) },
                    onScroll = { rows -> scrollFixedViewport(theme, viewport, frame, rows) },
                    onInteractiveStart = {
                        InputBox.enableInputScrolling(output)
                        output.write(Ansi.ENABLE_BRACKETED_PASTE + Ansi.ENABLE_MODIFY_OTHER_KEYS)
                        output.flush()
                    },
                    onInteractiveEnd = {
                        InputBox.disableInputScrolling(output)
                        output.write(Ansi.DISABLE_MODIFY_OTHER_KEYS + Ansi.DISABLE_BRACKETED_PASTE)
                        output.flush()
                    },
                    onUpdate = { snapshot -> renderFixedSnapshot(theme, viewport, frame, snapshot) },
                ),
            )

        finishFixedInput(theme, viewport, frame)
        return InputResult(input, frame.layout)
    }

    private fun renderFixedSnapshot(
        theme: Theme,
        viewport: FixedTerminalViewport,
        frame: FixedInputFrame,
        snapshot: LineEditorSnapshot,
    ) {
        frame.snapshot = snapshot
        output.inFrame(forceFlush = true) {
            output.write(Ansi.HIDE_CURSOR)
            try {
                val layoutChanged = refreshFixedLayout(theme, viewport, frame)
                val previousMenuRows = frame.menuRows
                val previousInputRows = frame.inputRows
                frame.menuRows =
                    InputBox.renderFixedEditor(
                        output = output,
                        layout = frame.layout,
                        theme = theme,
                        snapshot = snapshot,
                        previousMenuRows = previousMenuRows,
                        previousInputRows = previousInputRows,
                    )
                frame.inputRows = InputBox.editorTextRows(snapshot, frame.layout)
                val geometryChanged =
                    frame.menuRows != previousMenuRows || frame.inputRows != previousInputRows
                viewport.updateFollowGeometry(frame.layout, frame.menuRows, frame.inputRows)
                viewport.setFollowCursorRenderer {
                    frame.snapshot?.let { current ->
                        InputBox.positionFixedEditorCursor(output, frame.layout, current)
                    }
                }
                if (geometryChanged) {
                    InputBox.updateFixedOutputRegion(output, frame.layout, frame.menuRows, frame.inputRows)
                }
                if (layoutChanged || geometryChanged) {
                    viewport.redraw(frame.layout, frame.menuRows, frame.inputRows)
                    InputBox.positionFixedEditorCursor(output, frame.layout, snapshot)
                }
            } finally {
                output.write(Ansi.SHOW_CURSOR)
            }
        }
    }

    private fun scrollFixedViewport(
        theme: Theme,
        viewport: FixedTerminalViewport,
        frame: FixedInputFrame,
        rows: Int,
    ) {
        output.inFrame(forceFlush = true) {
            output.write(Ansi.HIDE_CURSOR)
            try {
                val layoutChanged = refreshFixedLayout(theme, viewport, frame)
                if (layoutChanged) viewport.redraw(frame.layout, frame.menuRows, frame.inputRows)
                viewport.scrollBy(rows, frame.layout, frame.menuRows, frame.inputRows)
                frame.snapshot?.let { snapshot ->
                    InputBox.positionFixedEditorCursor(output, frame.layout, snapshot)
                }
            } finally {
                output.write(Ansi.SHOW_CURSOR)
            }
        }
    }

    private fun refreshFixedLayout(
        theme: Theme,
        viewport: FixedTerminalViewport,
        frame: FixedInputFrame,
    ): Boolean {
        val nextLayout = createLayout(theme)
        if (nextLayout == frame.layout) return false
        InputBox.resizeFixedLayout(output, frame.layout, nextLayout, frame.menuRows, frame.inputRows)
        frame.layout = nextLayout
        viewport.updateFollowGeometry(frame.layout, frame.menuRows, frame.inputRows)
        return true
    }

    private fun finishFixedInput(
        theme: Theme,
        viewport: FixedTerminalViewport,
        frame: FixedInputFrame,
    ) {
        refreshFixedLayout(theme, viewport, frame)
        viewport.setFollowCursorRenderer(null)
        viewport.scrollToBottom(frame.layout)
        InputBox.restoreOutputCursor(output, frame.layout, theme, frame.menuRows, frame.inputRows)
        output.flush()
    }

    private fun approvalSnapshot(state: ApprovalMenuState): LineEditorSnapshot {
        val summary = approvalArgumentSummary(state.request.arguments)
        val title =
            buildString {
                append("Approval required · ")
                append(state.request.toolName)
                if (summary.isNotEmpty()) {
                    append(" · ")
                    append(summary)
                }
            }
        return LineEditorSnapshot(
            text = "/approval",
            cursor = "/approval".length,
            suggestions =
                listOf(
                    LineSuggestion("Allow once", "Run this tool call once"),
                    LineSuggestion("Allow for this session", "Allow future calls to this tool for this session"),
                    LineSuggestion("Deny", "Reject this tool call"),
                ),
            selectedSuggestionIndex = state.selectedIndex,
            recognizedCommandEnd = null,
            displayText = title,
            displayCursor = 0,
        )
    }

    private fun emptyInputSnapshot(): LineEditorSnapshot =
        LineEditorSnapshot(
            text = "",
            cursor = 0,
            suggestions = emptyList(),
            selectedSuggestionIndex = null,
            recognizedCommandEnd = null,
        )

    private fun approvalArgumentSummary(arguments: String): String =
        arguments
            .map { character ->
                if (character.code < CONTROL_CHARACTER_LIMIT || character.code == DELETE_CHARACTER) ' ' else character
            }.joinToString("")
            .replace(WHITESPACE, " ")
            .trim()
            .take(MAX_APPROVAL_ARGUMENT_CHARS)

    private fun readStaticInput(theme: Theme): String? {
        var lastSnapshot: LineEditorSnapshot? = null
        var menuRows = 0
        InputBox.renderStaticStart(output, theme, commands.suggestions.size)
        output.flush()

        val input =
            lineReader.readLine(
                LineReadRequest(
                    suggestions = commands.suggestions.map { LineSuggestion(it.name, it.description) },
                    commandNames = commands.commandNames,
                    inputWidth = InputBox::editorTextWidth,
                    onInteractiveStart = {
                        output.write(Ansi.ENABLE_BRACKETED_PASTE + Ansi.ENABLE_MODIFY_OTHER_KEYS)
                        output.flush()
                    },
                    onInteractiveEnd = {
                        output.write(Ansi.DISABLE_MODIFY_OTHER_KEYS + Ansi.DISABLE_BRACKETED_PASTE)
                        output.flush()
                    },
                    onUpdate = { snapshot ->
                        lastSnapshot = snapshot
                        menuRows = InputBox.renderStaticEditor(output, theme, snapshot, menuRows)
                        output.flush()
                    },
                ),
            )

        val snapshot = lastSnapshot
        if (snapshot == null) {
            InputBox.renderStaticEnd(output, theme, inputWasEchoed = Terminal.stdinIsTty())
        } else {
            InputBox.renderStaticInteractiveEnd(output, theme, snapshot, menuRows)
        }
        output.flush()
        return input
    }

    private fun printCommandMessage(
        text: String,
        tone: MessageTone,
        theme: Theme,
        target: Output,
    ) {
        target.writeLine(if (tone == MessageTone.ERROR) theme.error(text) else theme.dim(text))
        target.flush()
    }

    private fun ansiEnabled(env: Environment): Boolean =
        (Terminal.stdoutIsTty() || env.value("KOAKS_FORCE_COLOR").toBooleanFlagOrFalse() || fixedInputExplicitlyEnabled(env)) &&
            env.value("NO_COLOR") == null &&
            !env.value("KOAKS_NO_COLOR").toBooleanFlagOrFalse() &&
            env.value("TERM") != "dumb"

    private fun String?.toBooleanFlagOrFalse(): Boolean = this?.trim()?.lowercase() in setOf("1", "true", "yes", "y", "on")

    private fun createLayout(theme: Theme): TerminalLayout {
        val nativeSize = Terminal.size()
        val rows =
            environment.value("KOAKS_TERM_ROWS")?.toIntOrNull()
                ?: nativeSize?.rows
                ?: environment.value("LINES")?.toIntOrNull()
                ?: DEFAULT_TERM_ROWS
        val columns =
            environment.value("KOAKS_TERM_COLS")?.toIntOrNull()
                ?: nativeSize?.columns
                ?: environment.value("COLUMNS")?.toIntOrNull()
                ?: PANEL_WIDTH
        return TerminalLayout.of(
            rows = rows,
            columns = columns,
            fixedInput = theme.enabled && fixedInputEnabled(environment),
            commandMenuRows = commands.suggestions.size,
        )
    }

    private fun fixedInputEnabled(env: Environment): Boolean =
        when (env.value("KOAKS_FIXED_INPUT")?.lowercase()) {
            "0", "false", "no", "off" -> false
            "1", "true", "yes", "on" -> true
            else -> Terminal.stdinIsTty() && Terminal.stdoutIsTty()
        }

    private fun fixedInputExplicitlyEnabled(env: Environment): Boolean = env.value("KOAKS_FIXED_INPUT").toBooleanFlagOrFalse()

    private companion object {
        const val EVENT_CHANNEL_CAPACITY = 64
        const val CONTROL_CHARACTER_LIMIT = 32
        const val DELETE_CHARACTER = 127
        const val MAX_APPROVAL_ARGUMENT_CHARS = 240
        val SUBAGENT_ANIMATION_INTERVAL = 180.milliseconds
        val WHITESPACE = Regex("\\s+")
    }

    private fun SessionUpdateFailure.message(): String =
        when (this) {
            SessionUpdateFailure.BlankModel -> "/model cannot be blank."
            is SessionUpdateFailure.UnknownModel ->
                "Unknown model '$modelName'. Expected ${expected.joinToString(", ")}."
        }

    private data class InputResult(
        val input: String?,
        val layout: TerminalLayout,
    )

    private data class ConcurrentTurnResult(
        val input: String?,
        val layout: TerminalLayout,
    )

    private data class FixedInputFrame(
        var layout: TerminalLayout,
        var menuRows: Int = 0,
        var inputRows: Int = 1,
        var snapshot: LineEditorSnapshot? = null,
    )

    private sealed interface ConcurrentInputEvent {
        data object Started : ConcurrentInputEvent

        data object Ended : ConcurrentInputEvent

        data class Scroll(
            val rows: Int,
        ) : ConcurrentInputEvent

        data class Snapshot(
            val snapshot: LineEditorSnapshot,
        ) : ConcurrentInputEvent

        data class KeyPressed(
            val key: TerminalKey,
            val handled: CompletableDeferred<Boolean>,
        ) : ConcurrentInputEvent
    }

    private data class ApprovalMenuState(
        val request: ToolApprovalRequest,
        var selectedIndex: Int = 0,
    ) {
        fun handle(key: TerminalKey): ApprovalKeyOutcome =
            when (key) {
                TerminalKey.Up, TerminalKey.Left, TerminalKey.PageUp -> {
                    selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                    ApprovalKeyOutcome.Updated
                }
                TerminalKey.Home -> {
                    selectedIndex = 0
                    ApprovalKeyOutcome.Updated
                }
                TerminalKey.Down, TerminalKey.Right, TerminalKey.PageDown, TerminalKey.Tab -> {
                    selectedIndex = (selectedIndex + 1).coerceAtMost(2)
                    ApprovalKeyOutcome.Updated
                }
                TerminalKey.End -> {
                    selectedIndex = 2
                    ApprovalKeyOutcome.Updated
                }
                TerminalKey.Enter -> ApprovalKeyOutcome.Resolved(ApprovalDecision.entries[selectedIndex])
                TerminalKey.Escape -> ApprovalKeyOutcome.Resolved(ApprovalDecision.Deny)
                TerminalKey.EndOfInput ->
                    ApprovalKeyOutcome.Resolved(ApprovalDecision.Deny, consumeKey = false)
                is TerminalKey.Text ->
                    when (key.value.trim().lowercase()) {
                        "y", "yes", "a", "allow", "1" ->
                            ApprovalKeyOutcome.Resolved(ApprovalDecision.AllowOnce)
                        "s", "session", "2" -> ApprovalKeyOutcome.Resolved(ApprovalDecision.AllowForSession)
                        "n", "no", "d", "deny", "3" -> ApprovalKeyOutcome.Resolved(ApprovalDecision.Deny)
                        else -> ApprovalKeyOutcome.Consumed
                    }
                is TerminalKey.Paste ->
                    when (key.value.trim().lowercase()) {
                        "y", "yes", "allow", "1" -> ApprovalKeyOutcome.Resolved(ApprovalDecision.AllowOnce)
                        "s", "session", "2" -> ApprovalKeyOutcome.Resolved(ApprovalDecision.AllowForSession)
                        "n", "no", "deny", "3" -> ApprovalKeyOutcome.Resolved(ApprovalDecision.Deny)
                        else -> ApprovalKeyOutcome.Consumed
                    }
                TerminalKey.Backspace,
                TerminalKey.Delete,
                TerminalKey.LineBreak,
                -> ApprovalKeyOutcome.Consumed
            }
    }

    private sealed interface ApprovalKeyOutcome {
        data object Consumed : ApprovalKeyOutcome

        data object Updated : ApprovalKeyOutcome

        data class Resolved(
            val decision: ApprovalDecision,
            val consumeKey: Boolean = true,
        ) : ApprovalKeyOutcome
    }
}

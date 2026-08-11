package org.koaks.agent.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koaks.agent.app.command.CommandRegistry
import org.koaks.agent.config.AgentConfig
import org.koaks.agent.config.ConfigResolver
import org.koaks.agent.config.FileConfig
import org.koaks.agent.config.Provider
import org.koaks.agent.config.SessionPreferences
import org.koaks.agent.platform.Environment
import org.koaks.agent.tui.Ansi
import org.koaks.agent.tui.LineEditorSnapshot
import org.koaks.agent.tui.LineReadRequest
import org.koaks.agent.tui.LineReader
import org.koaks.agent.tui.Output
import org.koaks.agent.tui.TerminalKey
import org.koaks.framework.loop.AgentEvent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TerminalFrontendInteractionTest {
    @Test
    fun rendersTheSlashMenuAndSubmittedInputBlock() =
        runBlocking {
            val output = RecordingOutput()
            val frontend =
                TerminalFrontend(
                    session = ExitOnlySession(testConfig()),
                    trace = NoopFrontendTrace,
                    output = output,
                    lineReader = ExitLineReader,
                    environment = AnsiEnvironment,
                    commands = CommandRegistry.builtins(),
                )

            frontend.run()

            assertContains(output.content, "/help")
            assertContains(output.content, "/exit")
            assertContains(output.content, Ansi.USER_INPUT_BACKGROUND)
            assertContains(output.content, Ansi.ENTER_ALTERNATE_SCREEN)
            assertContains(output.content, Ansi.cursor(11, 4))
            assertContains(output.content, Ansi.USER_INPUT_BACKGROUND_FILL + "▄".repeat(118))
            assertContains(output.content, Ansi.cursor(1, 1) + Ansi.CLEAR_LINE)
        }

    @Test
    fun startsScrollInputBeforeTheActiveTurnCompletes() =
        runBlocking {
            val inputStarted = CompletableDeferred<Unit>()
            val releaseInput = CompletableDeferred<Unit>()
            val session = StreamingSession(testConfig(), inputStarted, releaseInput)
            val output = RecordingOutput()
            val frontend =
                TerminalFrontend(
                    session = session,
                    trace = NoopFrontendTrace,
                    output = output,
                    lineReader = StreamingLineReader(inputStarted, releaseInput),
                    environment = AnsiEnvironment,
                    commands = CommandRegistry.builtins(),
                )

            withTimeout(5.seconds) { frontend.run() }

            assertEquals(1, session.streamCount)
            assertContains(output.content, "line 20")
        }

    @Test
    fun rendersToolApprovalAsAnInteractiveSelectionMenu() =
        runBlocking {
            val approval = TerminalToolApproval()
            val requestStarted = CompletableDeferred<Unit>()
            val approvalResolved = CompletableDeferred<Unit>()
            val output = RecordingOutput()
            val frontend =
                TerminalFrontend(
                    session = ApprovalSession(testConfig(), approval, requestStarted, approvalResolved),
                    trace = NoopFrontendTrace,
                    output = output,
                    lineReader = ApprovalLineReader(requestStarted, approvalResolved),
                    environment = AnsiEnvironment,
                    commands = CommandRegistry.builtins(),
                    toolApproval = approval,
                )

            withTimeout(5.seconds) { frontend.run() }

            assertContains(output.content, "Approval required")
            assertContains(output.content, "Allow once")
            assertContains(output.content, "Deny")
            assertContains(output.content, "denied")
        }
}

private object ExitLineReader : LineReader {
    override fun readLine(): String? = error("Interactive LineReadRequest was not used.")

    override fun readLine(request: LineReadRequest): String {
        request.onInteractiveStart()
        request.onUpdate(
            LineEditorSnapshot(
                text = "/",
                cursor = 1,
                suggestions = request.suggestions,
                selectedSuggestionIndex = 0,
                recognizedCommandEnd = null,
            ),
        )
        request.onScroll(3)
        request.onUpdate(
            LineEditorSnapshot(
                text = "/exit",
                cursor = 5,
                suggestions = request.suggestions.filter { it.value == "/exit" },
                selectedSuggestionIndex = 0,
                recognizedCommandEnd = 5,
            ),
        )
        request.onInteractiveEnd()
        return "/exit"
    }
}

private class StreamingLineReader(
    private val inputStarted: CompletableDeferred<Unit>,
    private val releaseInput: CompletableDeferred<Unit>,
) : LineReader {
    private var readCount = 0

    override fun readLine(): String? = error("Interactive LineReadRequest was not used.")

    override fun readLine(request: LineReadRequest): String {
        readCount += 1
        request.onInteractiveStart()
        return if (readCount == 1) {
            request.onUpdate(
                LineEditorSnapshot(
                    text = "run",
                    cursor = 3,
                    suggestions = emptyList(),
                    selectedSuggestionIndex = null,
                    recognizedCommandEnd = null,
                ),
            )
            request.onInteractiveEnd()
            "run"
        } else {
            request.onUpdate(
                LineEditorSnapshot(
                    text = "",
                    cursor = 0,
                    suggestions = emptyList(),
                    selectedSuggestionIndex = null,
                    recognizedCommandEnd = null,
                ),
            )
            request.onScroll(3)
            inputStarted.complete(Unit)
            runBlocking { releaseInput.await() }
            request.onUpdate(
                LineEditorSnapshot(
                    text = "/exit",
                    cursor = 5,
                    suggestions = request.suggestions.filter { it.value == "/exit" },
                    selectedSuggestionIndex = 0,
                    recognizedCommandEnd = 5,
                ),
            )
            request.onInteractiveEnd()
            "/exit"
        }
    }
}

private class ApprovalLineReader(
    private val requestStarted: CompletableDeferred<Unit>,
    private val approvalResolved: CompletableDeferred<Unit>,
) : LineReader {
    private var readCount = 0

    override fun readLine(): String? = error("Interactive LineReadRequest was not used.")

    override fun readLine(request: LineReadRequest): String {
        readCount += 1
        request.onInteractiveStart()
        return if (readCount == 1) {
            request.onUpdate(emptySnapshot())
            request.onInteractiveEnd()
            "run"
        } else {
            request.onUpdate(emptySnapshot())
            runBlocking { requestStarted.await() }
            var menuHandled = false
            var attempts = 0
            while (!menuHandled && attempts < 100) {
                menuHandled = request.onKey(TerminalKey.Down)
                if (!menuHandled) runBlocking { delay(10) }
                attempts += 1
            }
            assertTrue(menuHandled)
            assertTrue(request.onKey(TerminalKey.Enter))
            runBlocking { approvalResolved.await() }
            request.onUpdate(
                LineEditorSnapshot(
                    text = "/exit",
                    cursor = 5,
                    suggestions = request.suggestions.filter { it.value == "/exit" },
                    selectedSuggestionIndex = 0,
                    recognizedCommandEnd = 5,
                ),
            )
            request.onInteractiveEnd()
            "/exit"
        }
    }
}

private class ExitOnlySession(
    override val config: AgentConfig,
) : ChatSessionPort {
    override fun updatePreferences(transform: (SessionPreferences) -> SessionPreferences) = Unit

    override fun stream(input: String): Flow<AgentEvent> = error("/exit must not start an Agent run.")
}

private class StreamingSession(
    override val config: AgentConfig,
    private val inputStarted: CompletableDeferred<Unit>,
    private val releaseInput: CompletableDeferred<Unit>,
) : ChatSessionPort {
    var streamCount: Int = 0
        private set

    override fun updatePreferences(transform: (SessionPreferences) -> SessionPreferences) = Unit

    override fun stream(input: String): Flow<AgentEvent> =
        flow {
            streamCount += 1
            inputStarted.await()
            repeat(20) { index -> emit(AgentEvent.TextDelta("line ${index + 1}\n")) }
            releaseInput.complete(Unit)
        }
}

private class ApprovalSession(
    override val config: AgentConfig,
    private val approval: TerminalToolApproval,
    private val requestStarted: CompletableDeferred<Unit>,
    private val approvalResolved: CompletableDeferred<Unit>,
) : ChatSessionPort {
    override fun updatePreferences(transform: (SessionPreferences) -> SessionPreferences) = Unit

    override fun stream(input: String): Flow<AgentEvent> =
        flow {
            requestStarted.complete(Unit)
            val allowed = approval.request("Bash", "{\"command\":\"echo hello\"}")
            emit(AgentEvent.TextDelta(if (allowed) "allowed\n" else "denied\n"))
            approvalResolved.complete(Unit)
        }
}

private object AnsiEnvironment : Environment {
    override fun get(key: String): String? =
        when (key) {
            "TERM" -> "xterm-256color"
            "KOAKS_FIXED_INPUT" -> "true"
            "KOAKS_TERM_ROWS" -> "12"
            "KOAKS_TERM_COLS" -> "120"
            else -> null
        }
}

private class RecordingOutput : Output {
    private val buffer = StringBuilder()
    val content: String get() = buffer.toString()

    override fun write(text: String) {
        buffer.append(text)
    }

    override fun writeLine(text: String) {
        buffer.append(text).append('\n')
    }

    override fun flush() = Unit
}

private fun emptySnapshot(): LineEditorSnapshot =
    LineEditorSnapshot(
        text = "",
        cursor = 0,
        suggestions = emptyList(),
        selectedSuggestionIndex = null,
        recognizedCommandEnd = null,
    )

private fun testConfig(): AgentConfig =
    ConfigResolver.resolve(
        FileConfig(
            schemaVersion = 1,
            defaultProvider = Provider.OPENAI,
            defaultModel = "gpt-test",
            providerOrder = listOf(Provider.OPENAI),
        ),
    )

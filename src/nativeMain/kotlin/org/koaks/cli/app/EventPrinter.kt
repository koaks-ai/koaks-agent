package org.koaks.cli.app

import org.koaks.cli.tui.LiveLinesOutput
import org.koaks.cli.tui.Output
import org.koaks.cli.tui.TerminalMarkdownRenderer
import org.koaks.cli.tui.TextUtil
import org.koaks.cli.tui.Theme
import org.koaks.framework.loop.AgentEvent
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class EventPrinter(
    private val showReasoning: Boolean,
    private val output: Output,
    private val theme: Theme,
    private val trace: CliTrace? = null,
) {
    private var assistantPromptActive = false
    private var reasoningPromptActive = false
    private var thinkingPlaceholderShown = false
    private var needsAssistantContinuationGap = false
    private var contentStarted = false
    private var endedWithNewLine = false
    private var hasFlushedStreamingContent = false
    private var unflushedStreamingChars = 0
    private var lastStreamingFlush = TimeSource.Monotonic.markNow()
    private val pendingToolCalls = linkedMapOf<String, PendingToolCall>()
    private val liveLinesOutput = output as? LiveLinesOutput
    private var subagentAnimationFrame = 0
    private var subagentProgressVisible = false
    private val markdown = TerminalMarkdownRenderer(
        theme = theme,
        blockWidth = PANEL_WIDTH,
        onFallback = { fallback ->
            trace?.markdownFallback(
                reason = fallback.reason,
                state = fallback.state,
                pendingChars = fallback.pendingChars,
                errorType = fallback.errorType,
            )
        },
    )

    fun print(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> {
                trace?.renderStage(event, "prompt.start")
                ensureAssistantPrompt()
                trace?.renderStage(event, "prompt.completed")
                trace?.renderStage(event, "markdown.start")
                val rendered = markdown.render(event.text)
                trace?.renderStage(event, "markdown.completed", rendered.length)
                markContent(rendered)
                trace?.renderStage(event, "stdout.write.start", rendered.length)
                output.write(rendered)
                trace?.renderStage(event, "stdout.write.completed", rendered.length)
                trace?.renderStage(event, "stdout.flush_check.start", rendered.length)
                flushStreamingOutputIfNeeded(rendered)
                trace?.renderStage(event, "stdout.flush_check.completed", rendered.length)
            }

            is AgentEvent.ReasoningDelta -> {
                if (showReasoning) {
                    ensureReasoningPrompt()
                    markContent(event.text)
                    val rendered = theme.dim(event.text)
                    output.write(rendered)
                    flushStreamingOutputIfNeeded(rendered)
                } else {
                    ensureThinkingPlaceholder()
                }
            }

            is AgentEvent.Completed -> {
                flushAssistantMarkdown()
                clearThinkingPlaceholder()
                flushPendingToolCalls()
                if (!endedWithNewLine) output.writeLine()
                flushOutput()
            }

            is AgentEvent.Terminated -> {
                flushAssistantMarkdown()
                clearThinkingPlaceholder()
                flushPendingToolCalls()
                if (!endedWithNewLine) output.writeLine()
                output.writeLine(theme.warn("[terminated] ${event.reason}"))
                flushOutput()
            }

            is AgentEvent.Failed -> {
                flushAssistantMarkdown()
                clearThinkingPlaceholder()
                flushPendingToolCalls()
                ensureLineStart()
                output.writeLine(theme.error("[error] ${event.error.message}"))
                flushOutput()
            }

            is AgentEvent.ToolCallRequested -> printToolCall(event)
            is AgentEvent.ToolResult -> printToolResult(event)
            is AgentEvent.StepCompleted -> Unit
        }
    }

    val hasActiveProgressAnimation: Boolean
        get() = theme.enabled && liveLinesOutput != null && pendingToolCalls.values.any { it.isSubagent }

    fun advanceProgressAnimation(flush: Boolean = true) {
        if (!hasActiveProgressAnimation) return
        subagentAnimationFrame = (subagentAnimationFrame + 1) % SUBAGENT_PROGRESS_FRAMES.size
        renderSubagentProgress(prefixOnly = true)
        if (flush) flushOutput()
    }

    private fun printToolCall(event: AgentEvent.ToolCallRequested) {
        flushAssistantMarkdown()
        clearThinkingPlaceholder()
        val wasReasoningActive = reasoningPromptActive
        ensureLineStart()
        if (wasReasoningActive) output.writeLine()

        val summary = summarizeToolArgs(event.call.arguments)
        val line = if (summary.isEmpty()) {
            "$TOOL_MARK ${event.call.name}"
        } else {
            "$TOOL_MARK ${event.call.name}  $summary"
        }
        val isSubagent = event.call.name.equals(SUBAGENT_TOOL_NAME, ignoreCase = true)
        if (isSubagent && pendingToolCalls.values.none { it.isSubagent }) {
            subagentAnimationFrame = 0
        }
        pendingToolCalls[event.call.id] = PendingToolCall(
            line = line,
            isSubagent = isSubagent,
        )
        if (hasActiveProgressAnimation) {
            renderSubagentProgress()
            markLineWritten()
        }
        assistantPromptActive = false
        reasoningPromptActive = false
        needsAssistantContinuationGap = true
        flushOutput()
    }

    private fun printToolResult(event: AgentEvent.ToolResult) {
        flushAssistantMarkdown()
        val pending = pendingToolCalls.remove(event.callId) ?: return
        clearSubagentProgress()
        ensureLineStart()

        val failed = event.isError || event.output.indicatesToolFailure()
        val status = if (failed) TOOL_ERROR_MARK else TOOL_SUCCESS_MARK
        val completedLine = "${pending.line}$TOOL_STATUS_GAP$status"
        output.writeLine(if (failed) theme.error(completedLine) else theme.dim(completedLine))

        markLineWritten()
        assistantPromptActive = false
        reasoningPromptActive = false
        needsAssistantContinuationGap = true
        renderSubagentProgress()
        flushOutput()
    }

    private fun ensureAssistantPrompt() {
        if (assistantPromptActive) return

        clearThinkingPlaceholder()
        flushPendingToolCalls()
        val wasReasoningActive = reasoningPromptActive
        ensureLineStart()
        if (wasReasoningActive) output.writeLine()
        if (needsAssistantContinuationGap) output.writeLine()
        needsAssistantContinuationGap = false
        assistantPromptActive = true
        reasoningPromptActive = false
    }

    private fun ensureReasoningPrompt() {
        if (reasoningPromptActive) return

        ensureLineStart()
        if (needsAssistantContinuationGap) output.writeLine()
//        output.write(theme.dim("[reasoning] "))
        contentStarted = true
        endedWithNewLine = false
        needsAssistantContinuationGap = false
        assistantPromptActive = false
        reasoningPromptActive = true
    }

    private fun ensureThinkingPlaceholder() {
        if (thinkingPlaceholderShown || contentStarted) return
        output.write(theme.dim("…"))
        thinkingPlaceholderShown = true
        contentStarted = true
        endedWithNewLine = false
        flushOutput()
    }

    private fun clearThinkingPlaceholder() {
        if (!thinkingPlaceholderShown) return
        // Move to a new line so the assistant answer / tool output does not trail the marker.
        if (!endedWithNewLine) {
            output.writeLine()
            endedWithNewLine = true
        }
        thinkingPlaceholderShown = false
    }

    private fun ensureLineStart() {
        if (contentStarted && !endedWithNewLine) {
            output.writeLine()
            endedWithNewLine = true
        }
    }

    private fun markContent(text: String) {
        contentStarted = true
        if (text.isNotEmpty()) endedWithNewLine = text.endsWith("\n")
    }

    private fun flushAssistantMarkdown() {
        val rendered = markdown.finish()
        if (rendered.isNotEmpty()) {
            markContent(rendered)
            output.write(rendered)
            flushStreamingOutputIfNeeded(rendered)
        }
    }

    private fun flushStreamingOutputIfNeeded(rendered: String) {
        if (rendered.isNotEmpty()) {
            unflushedStreamingChars += rendered.length
        } else if (!hasFlushedStreamingContent && unflushedStreamingChars == 0) {
            return
        }

        val shouldFlush =
            !hasFlushedStreamingContent ||
                rendered.contains('\n') ||
                unflushedStreamingChars >= STREAMING_FLUSH_CHARS ||
                lastStreamingFlush.elapsedNow() >= STREAMING_FLUSH_INTERVAL
        if (shouldFlush) flushOutput()
    }

    private fun flushOutput() {
        output.flush()
        hasFlushedStreamingContent = true
        unflushedStreamingChars = 0
        lastStreamingFlush = TimeSource.Monotonic.markNow()
    }

    private fun markLineWritten() {
        contentStarted = true
        endedWithNewLine = true
    }

    private fun flushPendingToolCalls() {
        if (pendingToolCalls.isEmpty()) return
        clearSubagentProgress()
        ensureLineStart()
        pendingToolCalls.values.forEach { call -> output.writeLine(theme.dim(call.line)) }
        pendingToolCalls.clear()
        markLineWritten()
    }

    private fun renderSubagentProgress(prefixOnly: Boolean = false) {
        val liveOutput = liveLinesOutput ?: return
        if (!theme.enabled) return
        val frame = SUBAGENT_PROGRESS_FRAMES[subagentAnimationFrame]
        val lines = pendingToolCalls.values
            .filter { it.isSubagent }
            .map { call -> theme.dim("$frame  ${call.line}") }
        if (prefixOnly && subagentProgressVisible) {
            liveOutput.replaceLiveLinePrefixes(
                lines = lines,
                prefixes = List(lines.size) { theme.dim(frame) },
            )
        } else {
            liveOutput.replaceLiveLines(lines)
        }
        subagentProgressVisible = lines.isNotEmpty()
    }

    private fun clearSubagentProgress() {
        if (!subagentProgressVisible) return
        liveLinesOutput?.replaceLiveLines(emptyList())
        subagentProgressVisible = false
    }

    private fun summarizeToolArgs(arguments: String): String {
        val description = extractJsonString(arguments, "description")
        if (description != null) {
            return truncateSummary(description.compactForLine())
        }

        val command = extractJsonString(arguments, "command")
        if (command != null) return truncateSummary(command.compactForLine())

        val path = extractJsonString(arguments, "path")
        if (path != null) {
            val fileName = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }
            val offset = extractJsonInt(arguments, "offset")
            val limit = extractJsonInt(arguments, "limit")
            val range = when {
                offset != null && limit != null -> "  $offset-${offset + limit - 1}"
                offset != null -> "  from $offset"
                limit != null -> "  1-$limit"
                else -> ""
            }
            return truncateSummary("$fileName$range")
        }

        val first = extractFirstJsonStringValue(arguments) ?: return ""
        return truncateSummary(first.compactForLine())
    }

    private fun truncateSummary(text: String): String {
        val maxWidth = (PANEL_WIDTH - TOOL_SUMMARY_PREFIX_RESERVE).coerceAtLeast(16)
        if (TextUtil.visibleWidth(text) <= maxWidth) return text
        return TextUtil.truncateVisible(text, (maxWidth - 3).coerceAtLeast(1)) + "..."
    }

    private fun String.compactForLine(): String =
        replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .joinToString(" ") { it.trim() }
            .trim()

    private fun String.indicatesToolFailure(): Boolean {
        val firstLine = replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .firstOrNull()
            ?.trimStart()
            .orEmpty()
        return firstLine.startsWith(TOOL_ERROR_MARK) || firstLine.startsWith("Error:", ignoreCase = true)
    }

    private companion object {
        const val TOOL_MARK = "▸"
        const val TOOL_SUCCESS_MARK = "✓"
        const val TOOL_ERROR_MARK = "✗"
        const val TOOL_STATUS_GAP = "    "
        const val TOOL_SUMMARY_PREFIX_RESERVE = 20
        const val SUBAGENT_TOOL_NAME = "Subagent"
        const val STREAMING_FLUSH_CHARS = 128
        val STREAMING_FLUSH_INTERVAL = 16.milliseconds
        val SUBAGENT_PROGRESS_FRAMES = listOf("□■■■", "■□■■", "■■□■", "■■■□")
        val JSON_STRING_FIELD_REGEX = Regex(""""([^"\\]+)"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val JSON_NUMBER_FIELD_REGEX = Regex(""""([^"\\]+)"\s*:\s*(-?\d+)""")

        fun extractJsonString(raw: String, key: String): String? {
            for (match in JSON_STRING_FIELD_REGEX.findAll(raw)) {
                if (match.groupValues[1] == key) return unescapeJsonString(match.groupValues[2])
            }
            return null
        }

        fun extractJsonInt(raw: String, key: String): Int? {
            for (match in JSON_NUMBER_FIELD_REGEX.findAll(raw)) {
                if (match.groupValues[1] == key) return match.groupValues[2].toIntOrNull()
            }
            return extractJsonString(raw, key)?.toIntOrNull()
        }

        fun extractFirstJsonStringValue(raw: String): String? =
            JSON_STRING_FIELD_REGEX.find(raw)?.let { unescapeJsonString(it.groupValues[2]) }

        fun unescapeJsonString(value: String): String = buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char == '\\' && index + 1 < value.length) {
                    when (val next = value[index + 1]) {
                        '"', '\\', '/' -> append(next)
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        'b' -> append('\b')
                        'f' -> append('\u000c')
                        'u' -> {
                            if (index + 5 < value.length) {
                                val hex = value.substring(index + 2, index + 6)
                                append(hex.toIntOrNull(16)?.toChar() ?: next)
                                index += 4
                            } else {
                                append(next)
                            }
                        }
                        else -> append(next)
                    }
                    index += 2
                } else {
                    append(char)
                    index += 1
                }
            }
        }
    }

    private data class PendingToolCall(
        val line: String,
        val isSubagent: Boolean,
    )
}

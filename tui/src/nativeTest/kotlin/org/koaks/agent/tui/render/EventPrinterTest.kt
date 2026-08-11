package org.koaks.agent.tui.render

import org.koaks.agent.tui.io.LiveLinesOutput
import org.koaks.agent.tui.io.Output
import org.koaks.agent.tui.state.PANEL_WIDTH
import org.koaks.framework.loop.AgentEvent
import org.koaks.framework.model.AgentError
import org.koaks.framework.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventPrinterTest {
    @Test
    fun printsFailureCauseStackTrace() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))
        val cause = IllegalStateException("write exploded")

        printer.print(
            AgentEvent.Failed(
                AgentError.ToolError(
                    toolName = "Write",
                    message = "Write tool failed",
                    retriable = false,
                    cause = cause,
                ),
            ),
        )

        assertContains(output.content(), "[error] Write tool failed")
        assertContains(output.content(), "IllegalStateException")
        assertContains(output.content(), "write exploded")
    }

    @Test
    fun continuesAssistantTextAfterToolWithoutRepeatingPrompt() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.TextDelta("让我查看一下当前文件夹的内容。\n"))
        printer.print(AgentEvent.ToolCallRequested(ToolCall("call-1", "Bash", """{"command":"ls -la"}""")))
        printer.print(AgentEvent.ToolResult("call-1", "file.txt", isError = false))
        printer.print(AgentEvent.TextDelta("当前文件夹包含 file.txt。"))

        assertEquals(
            """
            让我查看一下当前文件夹的内容。
            ▸ Bash  ls -la    ✓

            当前文件夹包含 file.txt。
            """.trimIndent(),
            output.content(),
        )
    }

    @Test
    fun summarizesSubagentToolWithDescription() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(
            AgentEvent.ToolCallRequested(
                ToolCall(
                    "call-1",
                    "Subagent",
                    """{"description":"Inspect auth module","prompt":"Find auth entry points"}""",
                ),
            ),
        )
        printer.print(AgentEvent.ToolResult("call-1", "[subagent] Inspect auth module\nfound AuthFilter", isError = false))

        assertEquals(
            "▸ Subagent  Inspect auth module    ✓",
            output.content().trimEnd(),
        )
    }

    @Test
    fun animatesSubagentUntilItsResultArrives() {
        val output = LiveBufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = true))

        printer.print(
            AgentEvent.ToolCallRequested(
                ToolCall(
                    "call-1",
                    "Subagent",
                    """{"description":"Inspect auth module","prompt":"Find auth entry points"}""",
                ),
            ),
        )

        assertTrue(printer.hasActiveProgressAnimation)
        assertContains(output.liveLines.single(), "□■■■  ▸ Subagent  Inspect auth module")

        printer.advanceProgressAnimation()

        assertContains(output.liveLines.single(), "■□■■  ▸ Subagent  Inspect auth module")
        assertEquals(1, output.prefixUpdateCount)

        printer.print(AgentEvent.ToolResult("call-1", "hidden result body", isError = false))

        assertFalse(printer.hasActiveProgressAnimation)
        assertTrue(output.liveLines.isEmpty())
        assertContains(output.content(), "▸ Subagent  Inspect auth module    ✓")
        assertFalse(output.content().contains("hidden result body"))
    }

    @Test
    fun keepsRemainingSubagentsAnimatedWhenOneCompletes() {
        val output = LiveBufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = true))

        printer.print(
            AgentEvent.ToolCallRequested(
                ToolCall("call-1", "Subagent", """{"description":"First","prompt":"one"}"""),
            ),
        )
        printer.print(
            AgentEvent.ToolCallRequested(
                ToolCall("call-2", "Subagent", """{"description":"Second","prompt":"two"}"""),
            ),
        )

        assertEquals(2, output.liveLines.size)

        printer.print(AgentEvent.ToolResult("call-1", "done", isError = false))

        assertEquals(1, output.liveLines.size)
        assertContains(output.liveLines.single(), "▸ Subagent  Second")
        assertTrue(printer.hasActiveProgressAnimation)
    }

    @Test
    fun printsAssistantPromptOnlyWhenTextStarts() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.ToolCallRequested(ToolCall("call-1", "Bash", """{"command":"ls"}""")))
        printer.print(AgentEvent.ToolResult("call-1", "file.txt", isError = false))
        printer.print(AgentEvent.TextDelta("当前文件夹有 file.txt"))

        assertEquals(
            """
            ▸ Bash  ls    ✓

            当前文件夹有 file.txt
            """.trimIndent(),
            output.content(),
        )
    }

    @Test
    fun printsReasoningSeparatelyWhenEnabled() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = true, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.ReasoningDelta("先分析一下"))
        printer.print(AgentEvent.ReasoningDelta("问题。"))
        printer.print(AgentEvent.TextDelta("答案来了。"))

        assertEquals(
            """
            先分析一下问题。

            答案来了。
            """.trimIndent(),
            output.content(),
        )
    }

    @Test
    fun showsThinkingPlaceholderWhenReasoningDisabled() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.ReasoningDelta("隐藏思考"))
        printer.print(AgentEvent.TextDelta("只显示答案。"))

        assertEquals("…\n只显示答案。", output.content())
    }

    @Test
    fun streamsShortCodeLineBeforeNewlineArrives() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.TextDelta("```text\n"))
        printer.print(AgentEvent.TextDelta("hi"))

        assertEquals(
            plainCodeBlockStart("text") + plainCodeLineOpen("hi"),
            output.content(),
        )

        printer.print(AgentEvent.TextDelta("\n```\n"))

        assertEquals(
            plainCodeBlockStart("text") + plainCodeLineOpen("hi") + "\r" +
                plainCodeBlock("text", "hi").removePrefix(plainCodeBlockStart("text")),
            output.content(),
        )
    }

    @Test
    fun opensCodeBlockFrameBeforeLanguageNewlineArrives() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.TextDelta("```"))

        assertEquals(
            plainCodeBlockStart("text"),
            output.content(),
        )

        printer.print(AgentEvent.TextDelta("kotlin\nval x = 1\n```\n"))

        assertEquals(
            plainCodeBlock("text", "val x = 1"),
            output.content(),
        )
    }

    @Test
    fun rendersAssistantMarkdownSubsetWithoutAnsiWhenThemeDisabled() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.TextDelta("用 **加粗** 和 `关键词`。\n"))
        printer.print(AgentEvent.TextDelta("```kotlin\nval answer = 42\n```\n"))

        assertEquals(
            "用 加粗 和 关键词。\n" + plainCodeBlock("kotlin", "val answer = 42"),
            output.content(),
        )
    }

    @Test
    fun rendersAssistantMarkdownSubsetAcrossStreamingDeltas() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.TextDelta("这是 **加"))
        printer.print(AgentEvent.TextDelta("粗** 和 `关键"))
        printer.print(AgentEvent.TextDelta("词`。"))

        assertEquals("这是 加粗 和 关键词。", output.content())
    }

    @Test
    fun streamsDelimitedSpanContentBeforeClosingDelimiterArrives() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.TextDelta("这是 **正在流式输出"))
        assertEquals("这是 正在流式输出", output.content())

        printer.print(AgentEvent.TextDelta("的粗体** 和 `代码"))
        assertEquals("这是 正在流式输出的粗体 和 代码", output.content())

        printer.print(AgentEvent.TextDelta("片段`。"))
        assertEquals("这是 正在流式输出的粗体 和 代码片段。", output.content())
    }

    @Test
    fun handlesInlineCodeOpenerSplitBeforeContent() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.TextDelta("拆分的反引号：`"))
        assertEquals("拆分的反引号：", output.content())

        printer.print(AgentEvent.TextDelta("code"))
        assertEquals("拆分的反引号：code", output.content())

        printer.print(AgentEvent.TextDelta("`。"))
        assertEquals("拆分的反引号：code。", output.content())
    }

    @Test
    fun rendersCodeBlockAtStartOnItsOwnLine() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.TextDelta("```kotlin\ncode block\n```\n"))

        assertEquals(
            plainCodeBlock("kotlin", "code block"),
            output.content(),
        )
    }

    @Test
    fun streamsCodeBlockContentBeforeClosingFenceArrives() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.TextDelta("```kotlin\n"))
        printer.print(AgentEvent.TextDelta("code block\n"))

        assertEquals(
            plainCodeBlockStart("kotlin") + plainCodeLine("code block"),
            output.content(),
        )

        printer.print(AgentEvent.TextDelta("```\n"))

        assertEquals(
            plainCodeBlock("kotlin", "code block"),
            output.content(),
        )
    }

    @Test
    fun streamsFullCodeLineChunkBeforeNewlineArrives() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))
        val fullWidthChunk = "x".repeat(PANEL_WIDTH - 4)

        printer.print(AgentEvent.TextDelta("```text\n$fullWidthChunk"))

        assertEquals(
            plainCodeBlockStart("text") + plainCodeLine(fullWidthChunk),
            output.content(),
        )
    }

    @Test
    fun rendersLargePlainTextDeltaWithoutChangingContent() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))
        val text = "plain text ".repeat(2_000)

        printer.print(AgentEvent.TextDelta(text))

        assertEquals(text, output.content())
    }

    @Test
    fun rendersAssistantMarkdownSubsetWithAnsiWhenThemeEnabled() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = true))

        printer.print(AgentEvent.TextDelta("**加粗** `关键词`\n```kotlin\nval answer = 42\n```\n"))

        val content = output.content()
        assertContains(content, "${Ansi.BOLD}加粗${Ansi.RESET}")
        assertContains(content, "${Ansi.BLUE}关键词${Ansi.RESET}")
        assertContains(content, "${Ansi.BOLD}${Ansi.CODE_LANGUAGE}kotlin${Ansi.RESET}")
        assertContains(content, "${Ansi.BOLD}${Ansi.CODE_KEYWORD}val${Ansi.RESET}")
        assertContains(content, "${Ansi.CODE_NUMBER}42${Ansi.RESET}")
    }

    @Test
    fun rendersAdditionalCodeLanguagesWithAnsiWhenThemeEnabled() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = true))

        listOf(
            "c" to "int main() { return 0; }\n",
            "cpp" to "class Box { public: int x; }\n",
            "java" to "public class Main {}\n",
            "python3" to "def greet(): # hello\n",
            "rust" to "fn main() { let x = 1; }\n",
            "js" to "const x = 1 // hello\n",
            "nodejs" to "function run() { return 1 }\n",
            "html" to "<div class=\"x\">hi</div>\n",
            "xml" to "<node id=\"1\" />\n",
        ).forEach { (language, code) ->
            printer.print(AgentEvent.TextDelta("```$language\n$code```\n"))
        }

        val content = output.content()
        listOf("c", "cpp", "java", "python3", "rust", "js", "nodejs", "html", "xml").forEach { language ->
            assertContains(content, "${Ansi.BOLD}${Ansi.CODE_LANGUAGE}$language${Ansi.RESET}")
        }
        listOf("int", "class", "public", "def", "fn", "const", "function", "div", "node").forEach { keyword ->
            assertContains(content, "${Ansi.BOLD}${Ansi.CODE_KEYWORD}$keyword${Ansi.RESET}")
        }
        assertContains(content, "${Ansi.CODE_COMMENT}# hello${Ansi.RESET}")
        assertContains(content, "${Ansi.CODE_COMMENT}// hello${Ansi.RESET}")
        assertContains(content, "${Ansi.CODE_STRING}\"x\"${Ansi.RESET}")
        assertContains(content, "${Ansi.CODE_STRING}\"1\"${Ansi.RESET}")
    }

    @Test
    fun wrapsLongCodeLinesInsteadOfTruncating() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))
        val longLine = "const message = \"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-tail\";"

        printer.print(AgentEvent.TextDelta("```js\n$longLine\n```\n"))

        assertEquals(
            plainCodeBlock(
                "js",
                "const message = \"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ01234",
                "56789-tail\";",
            ),
            output.content(),
        )
    }

    @Test
    fun separatesReasoningFromToolCallWhenEnabled() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = true, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.ReasoningDelta("先查一下。"))
        printer.print(AgentEvent.ToolCallRequested(ToolCall("call-1", "Bash", """{"command":"ls"}""")))
        printer.print(AgentEvent.ToolResult("call-1", "file.txt", isError = false))

        assertEquals(
            """
            先查一下。

            ▸ Bash  ls    ✓

            """.trimIndent(),
            output.content(),
        )
    }

    @Test
    fun printsToolCallAndSuccessStatusWithoutResultPreview() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(
            AgentEvent.ToolCallRequested(
                ToolCall(
                    id = "call-1",
                    name = "Bash",
                    arguments = """{"command":"ls"}""",
                ),
            ),
        )
        printer.print(
            AgentEvent.ToolResult(
                callId = "call-1",
                output = (1..6).joinToString("\n") { "line-$it" },
                isError = false,
            ),
        )

        assertEquals(
            "▸ Bash  ls    ✓\n",
            output.content(),
        )
    }

    @Test
    fun dimsToolCallLine() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = true))

        printer.print(AgentEvent.ToolCallRequested(ToolCall("call-1", "Bash", """{"command":"ls"}""")))
        printer.print(AgentEvent.ToolResult("call-1", "file.txt", isError = false))

        assertContains(output.content(), "${Ansi.DIM}▸ Bash  ls    ✓${Ansi.RESET}")
        assertFalse(output.content().contains("file.txt"))
    }

    @Test
    fun suppressesShortToolResultBody() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.ToolCallRequested(ToolCall("call-1", "Read", """{"path":"file.kt"}""")))
        printer.print(AgentEvent.ToolResult("call-1", "one\ntwo\nthree\nfour\nfive", isError = false))

        assertEquals("▸ Read  file.kt    ✓\n", output.content())
    }

    @Test
    fun truncatesLongToolCommandSummary() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))
        val command = "echo " + "x".repeat(120)

        printer.print(
            AgentEvent.ToolCallRequested(
                ToolCall("call-1", "PowerShell", """{"command":"$command"}"""),
            ),
        )
        printer.print(AgentEvent.ToolResult("call-1", "ok", isError = false))

        val line = output.content().trimEnd('\n')
        assertTrue(line.startsWith("▸ PowerShell  echo "))
        assertTrue(line.endsWith("...    ✓"))
        assertTrue(TextUtil.visibleWidth(line) <= PANEL_WIDTH)
    }

    @Test
    fun summarizesReadPathWithLineRange() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(
            AgentEvent.ToolCallRequested(
                ToolCall(
                    id = "call-1",
                    name = "Read",
                    arguments = """{"path":"D:\\DevLab\\Kotlin\\koaks\\runtime\\AgentRuntime.kt","offset":66,"limit":50}""",
                ),
            ),
        )
        printer.print(AgentEvent.ToolResult("call-1", "content", isError = false))

        assertEquals("▸ Read  AgentRuntime.kt  66-115    ✓\n", output.content())
    }

    @Test
    fun suppressesCompactToolResultBody() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.ToolCallRequested(ToolCall("call-1", "PowerShell", """{"command":"ls"}""")))
        printer.print(
            AgentEvent.ToolResult(
                callId = "call-1",
                output =
                    """
                    ✓ exit 0
                    FullName
                    D:\DevLab\Kotlin\koaks\runtime\src\AgentRuntime.kt
                    extra-1
                    extra-2
                    extra-3
                    """.trimIndent(),
                isError = false,
            ),
        )

        assertEquals("▸ PowerShell  ls    ✓\n", output.content())
    }

    @Test
    fun printsOkStatusFromToolOutput() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.ToolCallRequested(ToolCall("call-1", "Bash", """{"command":"true"}""")))
        printer.print(
            AgentEvent.ToolResult(
                callId = "call-1",
                output = "✓ exit 0",
                isError = false,
            ),
        )

        assertEquals("▸ Bash  true    ✓\n", output.content())
    }

    @Test
    fun printsFailureStatusWithoutToolOutputBody() {
        val output = BufferOutput()
        val printer = EventPrinter(showReasoning = false, output = output, theme = Theme(enabled = false))

        printer.print(AgentEvent.ToolCallRequested(ToolCall("call-1", "Bash", """{"command":"false"}""")))
        printer.print(
            AgentEvent.ToolResult(
                callId = "call-1",
                output = "✗ exit 1\nbombed",
                isError = false,
            ),
        )

        assertEquals("▸ Bash  false    ✗\n", output.content())
    }
}

private class BufferOutput : Output {
    private val builder = StringBuilder()

    override fun write(text: String) {
        builder.append(text)
    }

    override fun writeLine(text: String) {
        builder.append(text).append('\n')
    }

    override fun flush() = Unit

    fun content(): String = builder.toString()
}

private class LiveBufferOutput : LiveLinesOutput {
    private val builder = StringBuilder()
    var liveLines: List<String> = emptyList()
        private set
    var prefixUpdateCount: Int = 0
        private set

    override fun write(text: String) {
        builder.append(text)
    }

    override fun writeLine(text: String) {
        builder.append(text).append('\n')
    }

    override fun replaceLiveLines(lines: List<String>) {
        liveLines = lines.toList()
    }

    override fun replaceLiveLinePrefixes(
        lines: List<String>,
        prefixes: List<String>,
    ) {
        liveLines = lines.toList()
        prefixUpdateCount += 1
    }

    override fun flush() = Unit

    fun content(): String = builder.toString()
}

private fun plainCodeBlock(
    language: String,
    vararg lines: String,
): String =
    buildString {
        append(plainCodeBlockStart(language))
        lines.forEach { append(plainCodeLine(it)) }
        append(plainCodeBlockEnd())
    }

private fun plainCodeBlockStart(language: String): String {
    val remaining = PANEL_WIDTH - "┌─ ".length - language.length - "┐".length
    return "┌─ $language${"─".repeat(remaining)}┐\n"
}

private fun plainCodeLine(line: String): String = plainCodeLineOpen(line) + "\n"

private fun plainCodeLineOpen(line: String): String = "│ ${line.padEnd(PANEL_WIDTH - 4)} │"

private fun plainCodeBlockEnd(): String = "└${"─".repeat(PANEL_WIDTH - 2)}┘\n"

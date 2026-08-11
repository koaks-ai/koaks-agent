package org.koaks.agent.tui.render

import org.koaks.agent.credential.CredentialSource
import org.koaks.agent.provider.Provider
import org.koaks.agent.session.CredentialSummary
import org.koaks.agent.session.SessionSnapshot
import org.koaks.agent.tui.io.Output
import org.koaks.framework.memory.ThreadId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WelcomeViewTest {
    @Test
    fun rendersWelcomeWithTheSameAccentBlockAsUserMessages() {
        val snapshot = testSnapshot(historyMessages = 42)
        val output = WelcomeOutput()

        WelcomeView.render(snapshot, output, Theme(enabled = false), clearScreen = false, width = 48)

        val lines = output.content.lines().dropLastWhile(String::isEmpty)
        assertEquals(
            listOf(
                "╻",
                "┃  Koaks Agent",
                "┃  ",
                "┃  gpt-test",
                "┃  openai · test-thread · 42 messages",
                "┃  ",
                "┃  /help commands    /exit quit",
                "╹",
            ),
            lines,
        )
        assertFalse(output.content.contains('█'))
    }

    @Test
    fun keepsOriginalPurpleAccentWhenColorsAreEnabled() {
        val snapshot = testSnapshot()
        val output = WelcomeOutput()

        WelcomeView.render(snapshot, output, Theme(enabled = true), clearScreen = false, width = 48)

        assertContains(output.content, "${Ansi.WELCOME_BORDER}┃${Ansi.RESET}")
        assertFalse(output.content.contains("${Ansi.USER_INPUT_BORDER}┃${Ansi.RESET}"))
    }
}

private fun testSnapshot(historyMessages: Int = 1024): SessionSnapshot =
    SessionSnapshot(
        provider = Provider.OPENAI,
        modelName = "gpt-test",
        baseUrl = Provider.OPENAI.defaultBaseUrl,
        credential = CredentialSummary.Reference(CredentialSource.ENVIRONMENT, "OPENAI_API_KEY"),
        threadId = ThreadId("test-thread"),
        historyMessages = historyMessages,
        reasoningEnabled = false,
        skillPaths = emptyList(),
        skills = emptyList(),
        availableProviders = listOf(Provider.OPENAI),
        availableModels = listOf("gpt-test"),
    )

private class WelcomeOutput : Output {
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

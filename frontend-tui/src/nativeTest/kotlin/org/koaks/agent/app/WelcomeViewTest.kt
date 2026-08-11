package org.koaks.agent.app

import org.koaks.agent.config.ConfigResolver
import org.koaks.agent.config.FileConfig
import org.koaks.agent.config.Provider
import org.koaks.agent.tui.Ansi
import org.koaks.agent.tui.Output
import org.koaks.agent.tui.Theme
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WelcomeViewTest {
    @Test
    fun rendersWelcomeWithTheSameAccentBlockAsUserMessages() {
        val config =
            ConfigResolver.resolve(
                FileConfig(
                    schemaVersion = 1,
                    defaultProvider = Provider.OPENAI,
                    defaultModel = "gpt-test",
                    threadId = "test-thread",
                    historyMessages = 42,
                    providerOrder = listOf(Provider.OPENAI),
                ),
            )
        val output = WelcomeOutput()

        WelcomeView.render(config, output, Theme(enabled = false), clearScreen = false, width = 48)

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
        val config =
            ConfigResolver.resolve(
                FileConfig(
                    schemaVersion = 1,
                    defaultProvider = Provider.OPENAI,
                    defaultModel = "gpt-test",
                    providerOrder = listOf(Provider.OPENAI),
                ),
            )
        val output = WelcomeOutput()

        WelcomeView.render(config, output, Theme(enabled = true), clearScreen = false, width = 48)

        assertContains(output.content, "${Ansi.WELCOME_BORDER}┃${Ansi.RESET}")
        assertFalse(output.content.contains("${Ansi.USER_INPUT_BORDER}┃${Ansi.RESET}"))
    }
}

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

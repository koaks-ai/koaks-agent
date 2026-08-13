package org.koaks.agent.tui.render

import org.koaks.agent.session.SessionSnapshot
import org.koaks.agent.tui.input.InputBox
import org.koaks.agent.tui.io.Output
import org.koaks.agent.tui.state.PANEL_WIDTH

internal object WelcomeView {
    fun render(
        config: SessionSnapshot,
        output: Output,
        theme: Theme,
        clearScreen: Boolean,
        width: Int = PANEL_WIDTH,
    ) {
        if (clearScreen && theme.enabled) {
            output.write("${Ansi.CLEAR_SCREEN}${Ansi.HOME}")
        }

        val context = "${config.provider.id} · ${config.threadId.value} · ${config.historyMessages} messages"

        InputBox.renderContentBlock(
            output = output,
            theme = theme,
            lines =
                listOf(
                    theme.bold(BRAND),
                    "",
                    config.modelName,
                    theme.dim(context),
                    "",
                    "${theme.inputCommand("/help")} commands    ${theme.inputCommand("/exit")} quit",
                ),
            width = width,
            accent = InputBox.BlockAccent.WELCOME,
        )
    }

    private const val BRAND = "Koaks Agent"
}

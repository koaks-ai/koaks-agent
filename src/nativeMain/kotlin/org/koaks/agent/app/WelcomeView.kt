package org.koaks.agent.app

import org.koaks.agent.config.AgentConfig
import org.koaks.agent.tui.Ansi
import org.koaks.agent.tui.Output
import org.koaks.agent.tui.Theme

internal object WelcomeView {

    fun render(
        config: AgentConfig,
        output: Output,
        theme: Theme,
        clearScreen: Boolean,
        width: Int = PANEL_WIDTH,
    ) {
        if (clearScreen && theme.enabled) {
            output.write("${Ansi.CLEAR_SCREEN}${Ansi.HOME}")
        }

        val context = "${config.provider.id} · ${config.threadId} · ${config.historyMessages} messages"

        InputBox.renderContentBlock(
            output = output,
            theme = theme,
            lines = listOf(
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

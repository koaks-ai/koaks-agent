package org.koaks.agent.app

import org.koaks.agent.tui.Output
import org.koaks.agent.tui.TerminalLayout
import org.koaks.agent.tui.Theme

internal data class AgentContext(
    val session: CliChatSession,
    val output: Output,
    val theme: Theme,
    val layout: TerminalLayout,
) {
    val config get() = session.config
}

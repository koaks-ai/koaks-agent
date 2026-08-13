package org.koaks.agent.tui.input

import org.koaks.agent.tui.platform.Terminal
import kotlin.test.Test
import kotlin.test.assertFalse

class JvmTerminalAdapterTest {
    @Test
    fun nonInteractiveJvmDoesNotClaimAtty() {
        assertFalse(Terminal.stdinIsTty())
        assertFalse(Terminal.stdoutIsTty())
    }
}

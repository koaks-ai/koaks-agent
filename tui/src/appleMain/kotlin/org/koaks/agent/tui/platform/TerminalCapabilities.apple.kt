@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.koaks.agent.tui.platform

import platform.posix.isatty

internal actual fun platformStdinIsTty(): Boolean = isatty(0) == 1

internal actual fun platformStdoutIsTty(): Boolean = isatty(1) == 1

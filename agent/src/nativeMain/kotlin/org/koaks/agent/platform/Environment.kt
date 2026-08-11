@file:OptIn(ExperimentalForeignApi::class)

package org.koaks.agent.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

public interface Environment {
    public fun get(key: String): String?
}

public object PosixEnvironment : Environment {
    override fun get(key: String): String? = getenv(key)?.toKString()
}

public fun Environment.value(key: String): String? = get(key)?.trim()?.takeIf { it.isNotEmpty() }

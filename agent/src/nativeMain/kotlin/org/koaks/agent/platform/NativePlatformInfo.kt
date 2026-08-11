@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package org.koaks.agent.platform

import kotlin.native.Platform

public object NativePlatformInfo {
    public val operatingSystemName: String
        get() =
            when (Platform.osFamily.name) {
                "MACOSX" -> "macOS"
                "IOS" -> "iOS"
                "WINDOWS" -> "Windows"
                "LINUX" -> "Linux"
                "ANDROID" -> "Android"
                "TVOS" -> "tvOS"
                "WATCHOS" -> "watchOS"
                else -> Platform.osFamily.name.lowercase()
            }

    public fun workingDirectory(): String = NativeFileSystem.workingDirectory()
}

internal val currentOperatingSystemName: String
    get() = NativePlatformInfo.operatingSystemName

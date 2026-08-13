@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.koaks.agent.platform

import kotlin.native.Platform

public actual object PlatformInfo {
    public actual val operatingSystemName: String
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

    public actual fun workingDirectory(): String = PlatformFileSystem.workingDirectory()
}

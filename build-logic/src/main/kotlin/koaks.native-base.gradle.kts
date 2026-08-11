plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.diffplug.spotless")
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    macosArm64()
    if (providers.gradleProperty("koaksEnableMacosX64").orNull.toBoolean()) {
        macosX64()
    }
    mingwX64("windowsX64")

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.6.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.6.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

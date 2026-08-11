plugins {
    id("com.diffplug.spotless") version "7.2.1" apply false
}

group = "org.koaks.agent"
version = "1.0-SNAPSHOT"

allprojects {
    group = rootProject.group
    version = rootProject.version
}

subprojects {
    apply(plugin = "com.diffplug.spotless")

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
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
}

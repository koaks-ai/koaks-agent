plugins {
    kotlin("multiplatform") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
}

group = "org.koaks.agent"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)

    macosArm64("macosArm") {
        binaries {
            executable {
                baseName = "koaks-agent"
                entryPoint = "org.koaks.agent.main"
            }
        }
    }

    mingwX64("windowsX64") {
        binaries {
            executable {
                baseName = "koaks-agent"
                entryPoint = "org.koaks.agent.main"
            }
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            dependencies {
                implementation("org.koaks:koaks-core:${property("koaksVersion")}")
                implementation("org.koaks:koaks-model-anthropic:${property("koaksVersion")}")
                implementation("org.koaks:koaks-model-ollama:${property("koaksVersion")}")
                implementation("org.koaks:koaks-model-openai:${property("koaksVersion")}")
                implementation("org.koaks:koaks-model-qwen:${property("koaksVersion")}")
            }
        }

        nativeTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

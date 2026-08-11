plugins {
    id("koaks.native-library")
}

kotlin {
    macosArm64 {
        binaries.executable {
            baseName = "koaks-agent"
            entryPoint = "org.koaks.agent.bootstrap.main"
        }
    }
    targets
        .findByName("macosX64")
        ?.let { it as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget }
        ?.binaries
        ?.executable {
            baseName = "koaks-agent"
            entryPoint = "org.koaks.agent.bootstrap.main"
        }
    targets.named<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>("windowsX64") {
        binaries.executable {
            baseName = "koaks-agent"
            entryPoint = "org.koaks.agent.bootstrap.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.koaks:koaks-core:${property("koaksVersion")}")
            implementation(project(":agent-core"))
            implementation(project(":frontend-tui"))
        }
    }
}

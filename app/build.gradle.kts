plugins {
    id("koaks.multiplatform-application")
}

koaksMultiplatformApplication {
    baseName.set("koaks-agent")
    entryPoint.set("org.koaks.agent.cli.main")
    jvmMainClass.set("org.koaks.agent.cli.MainJvmKt")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("org.koaks:koaks-core:${property("koaksVersion")}")
            implementation(project(":agent"))
            implementation(project(":tui"))
        }
    }
}

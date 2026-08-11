plugins {
    id("koaks.native-application")
}

koaksNativeApplication {
    baseName.set("koaks-agent")
    entryPoint.set("org.koaks.agent.cli.main")
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

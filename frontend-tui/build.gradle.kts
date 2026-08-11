plugins {
    id("koaks.native-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api("org.koaks:koaks-core:${property("koaksVersion")}")
            implementation(project(":agent-core"))
        }
    }
}

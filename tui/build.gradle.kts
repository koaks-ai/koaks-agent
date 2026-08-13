plugins {
    id("koaks.multiplatform-library")
}

val jlineVersion = "3.30.6"

kotlin {
    sourceSets {
        jvmMain.dependencies {
            implementation("org.jline:jline-terminal:$jlineVersion")
            implementation("org.jline:jline-terminal-jna:$jlineVersion")
        }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api("org.koaks:koaks-core:${property("koaksVersion")}")
            api(project(":agent"))
        }
    }
}

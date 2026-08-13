plugins {
    id("koaks.multiplatform-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api("org.koaks:koaks-core:${property("koaksVersion")}")
            implementation("org.koaks:koaks-model-anthropic:${property("koaksVersion")}")
            implementation("org.koaks:koaks-model-ollama:${property("koaksVersion")}")
            implementation("org.koaks:koaks-model-openai:${property("koaksVersion")}")
            implementation("org.koaks:koaks-model-qwen:${property("koaksVersion")}")
        }
    }
}

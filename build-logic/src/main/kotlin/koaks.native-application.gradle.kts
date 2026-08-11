import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("koaks.native-base")
}

val application = extensions.create<KoaksNativeApplicationExtension>("koaksNativeApplication")

afterEvaluate {
    val configuredBaseName = application.baseName.get()
    val configuredEntryPoint = application.entryPoint.get()
    kotlin.targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            baseName = configuredBaseName
            entryPoint = configuredEntryPoint
        }
    }
}

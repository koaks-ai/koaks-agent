import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.JavaExec
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("koaks.multiplatform-base")
    id("com.gradleup.shadow")
}

val application = extensions.create<KoaksMultiplatformApplicationExtension>("koaksMultiplatformApplication")

afterEvaluate {
    val configuredBaseName = application.baseName.get()
    val configuredEntryPoint = application.entryPoint.get()
    val configuredJvmMainClass = application.jvmMainClass.get()
    kotlin.targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            baseName = configuredBaseName
            entryPoint = configuredEntryPoint
        }
    }

    val jvmRuntimeClasspath = configurations.getByName("jvmRuntimeClasspath")
    tasks.register<JavaExec>("runJvm") {
        group = "application"
        description = "Runs the JVM application. Pass arguments with -PappArgs=\"...\"."
        mainClass.set(configuredJvmMainClass)
        dependsOn("jvmJar")
        classpath = files(tasks.named("jvmJar"), jvmRuntimeClasspath)
        standardInput = System.`in`
        jvmArgs(
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
        )
        val configuredArgs = providers.gradleProperty("appArgs").orNull
        if (!configuredArgs.isNullOrBlank()) {
            args = configuredArgs.split(Regex("\\s+"))
        }
    }

    val jvmCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
    tasks.register<ShadowJar>("jvmFatJar") {
        group = "build"
        description = "Assembles a self-contained executable JVM jar."
        archiveFileName.set("$configuredBaseName.jar")
        destinationDirectory.set(layout.buildDirectory.dir("libs"))
        manifest.attributes["Main-Class"] = configuredJvmMainClass
        from(jvmCompilation.output.allOutputs)
        configurations = listOf(jvmRuntimeClasspath)
        mergeServiceFiles()
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    }
}

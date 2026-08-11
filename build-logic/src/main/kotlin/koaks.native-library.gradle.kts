plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    macosArm64()
    if (providers.gradleProperty("koaksEnableMacosX64").orNull.toBoolean()) {
        macosX64()
    }
    mingwX64("windowsX64")

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

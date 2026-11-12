// Never on the test classpath directly: see fixtures-scala3/build.gradle.kts for why.
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    kotlin("jvm")
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmDefault.set(JvmDefaultMode.DISABLE)
    }
}

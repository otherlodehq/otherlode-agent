plugins {
    kotlin("jvm") version "2.2.21"
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

dependencies {
    // Compile-time only: at runtime OtherlodeEndpoints comes from the target JVM's bootstrap
    // classloader, where BootstrapHolder appends the embedded jar (see the root project's
    // build.gradle.kts for the full rationale).
    compileOnly(project(":bootstrap"))
}

kotlin {
    jvmToolchain(21)
}

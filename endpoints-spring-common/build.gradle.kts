plugins {
    kotlin("jvm") version "2.2.21"
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

dependencies {
    // Compile-time only: at runtime OtherlodeEndpoints comes from the target JVM's bootstrap classloader.
    compileOnly(project(":bootstrap"))
}

kotlin {
    jvmToolchain(21)
}

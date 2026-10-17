plugins {
    kotlin("jvm") version "2.2.21"
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

val byteBuddyVersion = "1.18.12"

dependencies {
    // Compile-time only: at runtime ByteBuddy comes from the root project's own dependency once
    // this module's classes are merged into the shaded agent jar.
    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion")
}

kotlin {
    jvmToolchain(21)
}

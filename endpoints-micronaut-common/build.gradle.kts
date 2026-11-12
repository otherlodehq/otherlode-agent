plugins {
    kotlin("jvm") version "2.2.21"
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

val byteBuddyVersion = "1.18.12"

dependencies {
    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion")
}

kotlin {
    jvmToolchain(21)
}

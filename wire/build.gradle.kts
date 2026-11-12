plugins {
    kotlin("jvm") version "2.2.21"
    id("com.google.protobuf") version "0.9.4"
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

dependencies {
    api("com.google.protobuf:protobuf-java:3.25.5")
}

// buf.yaml and the Buf workflow publish the schema from this path.
sourceSets.main {
    proto {
        srcDir(rootProject.file("src/main/proto"))
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
}

kotlin {
    jvmToolchain(21)
    coreLibrariesVersion = "2.0.21"
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

tasks.jar {
    archiveBaseName.set("otherlode-wire")
}

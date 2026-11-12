import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("org.springframework.boot")
    kotlin("jvm")
    kotlin("plugin.spring")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    compileOnly("jakarta.servlet:jakarta.servlet-api")

    // Without transitives, so the jar list holds only the libraries the soak test plants a status for.
    implementation("com.google.guava:guava:33.7.2-jre") { isTransitive = false }
    implementation("org.apache.commons:commons-csv:1.14.1") { isTransitive = false }
    runtimeOnly("org.apache.commons:commons-text:1.14.0") { isTransitive = false }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

springBoot {
    mainClass.set("dev.soaktest.shop.ShopApplicationKt")
}

tasks.bootJar {
    archiveFileName.set("soak-shop.jar")
}

tasks.jar {
    enabled = false
}

import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("org.springframework.boot") version "4.1.1"
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    // Never used, so the stub collector's dependency report shows it as unloaded.
    runtimeOnly("org.apache.commons:commons-lang3")
}

kotlin {
    jvmToolchain(21)
}

springBoot {
    mainClass.set("com.example.demo.spring.SpringDemoApplicationKt")
}

tasks.bootJar {
    archiveFileName.set("demo-spring.jar")
}

tasks.jar {
    enabled = false
}

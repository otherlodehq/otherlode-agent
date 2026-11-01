import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:3.5.2")
    implementation("io.ktor:ktor-server-cio:3.5.2")
    implementation("io.ktor:ktor-server-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("ch.qos.logback:logback-classic:1.5.38")
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

// Each version has its own source set on top of the common sources: src/v1 holds ReplayEngine and
// the replay route, src/v2 holds the stats route. -PeventsVersion=1.0.0 or 2.0.0 builds one jar;
// the default builds both.
val versionSourceSets = mapOf("1.0.0" to "v1", "2.0.0" to "v2")
val wanted = providers.gradleProperty("eventsVersion").orNull

val shadowJars =
    versionSourceSets.map { (version, name) ->
        val versionSourceSet = sourceSets.create(name)
        kotlin.target.compilations
            .getByName(name)
            .associateWith(kotlin.target.compilations.getByName("main"))
        configurations[versionSourceSet.implementationConfigurationName].extendsFrom(configurations.implementation.get())

        tasks.register<ShadowJar>("shadowJar${name.uppercase()}") {
            group = "build"
            description = "Builds soak-events-$version.jar."
            archiveFileName.set("soak-events-$version.jar")
            from(sourceSets.main.get().output, versionSourceSet.output)
            configurations = listOf(project.configurations.runtimeClasspath.get())
            mergeServiceFiles()
            manifest {
                attributes["Main-Class"] = "dev.soaktest.events.MainKt"
            }
            onlyIf { wanted == null || wanted == version }
        }
    }

tasks.assemble {
    dependsOn(shadowJars)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

tasks.jar {
    enabled = false
}

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

val codec by configurations.creating {
    isTransitive = false
}

dependencies {
    codec("commons-codec:commons-codec:1.19.0")
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

val versionSourceSets = mapOf("1.0.0" to "v1", "2.0.0" to "v2")
val wanted = providers.gradleProperty("eventsVersion").orNull

// 2.0.0 names commons-codec in its manifest Class-Path. Only LegacyDigest, a class nothing loads,
// refers to it.
val codecJarName = "commons-codec-1.19.0.jar"
val copyCodec =
    tasks.register<Copy>("copyCodec") {
        from(codec)
        into(layout.buildDirectory.dir("libs/lib"))
    }

val shadowJars =
    versionSourceSets.map { (version, name) ->
        val versionSourceSet = sourceSets.create(name)
        if (version == "2.0.0") {
            dependencies.add(versionSourceSet.compileOnlyConfigurationName, "commons-codec:commons-codec:1.19.0")
        }
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
                if (version == "2.0.0") {
                    attributes["Class-Path"] = "lib/$codecJarName"
                }
            }
            if (version == "2.0.0") {
                dependsOn(copyCodec)
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

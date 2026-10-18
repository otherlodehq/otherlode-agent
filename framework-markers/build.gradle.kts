// The fixtures that prove each framework marker (ADR 0072): per framework and release, a JVM that
// uses only the framework's client side or no routing, one that configures its routing without
// serving, and one that serves a request, each run with -Xlog:class+load. Nothing here ships. The
// small programs in src/main/java are compiled against the newest release of each framework and
// run against every release listed below.
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    kotlin("jvm") version "2.2.21"
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

val slf4jVersion = "2.0.16"

// Every release a marker is proved against: framework, release, and the artifacts that make its
// runtime classpath. A release is named by its framework and version in the test's system
// properties.
val scenarioArtifacts: Map<String, Map<String, List<String>>> =
    mapOf(
        "grpc" to
            listOf("1.85.0", "1.70.0", "1.60.0", "1.40.1", "1.30.2").associateWith { v ->
                listOf("io.grpc:grpc-core:$v", "io.grpc:grpc-stub:$v", "io.grpc:grpc-netty-shaded:$v")
            },
        "armeria" to
            listOf("1.42.0", "1.30.0", "1.20.0", "1.10.0", "1.0.0").associateWith { v ->
                listOf("com.linecorp.armeria:armeria:$v", "org.slf4j:slf4j-nop:$slf4jVersion")
            },
        "undertow" to
            listOf("2.4.1.Final", "2.3.26.Final", "2.2.37.Final").associateWith { v ->
                listOf("io.undertow:undertow-core:$v")
            },
        "javalin" to
            listOf("5.6.5", "5.3.2", "5.0.0").associateWith { v ->
                listOf("io.javalin:javalin:$v", "org.slf4j:slf4j-nop:$slf4jVersion")
            },
        "webflux" to
            mapOf("7.0.9" to "1.3.7", "6.2.19" to "1.2.18", "5.3.39" to "1.0.48").mapValues { (spring, netty) ->
                listOf(
                    "org.springframework:spring-webflux:$spring",
                    "org.springframework:spring-context:$spring",
                    "io.projectreactor.netty:reactor-netty-http:$netty",
                )
            },
    )

// Javalin releases a module covers: the marker class must not exist in any of them.
val javalinCovered = listOf("6.1.0", "6.7.0", "7.0.0", "7.2.3")

// Javalin releases older than 5, which carry the same Jetty class: serving must not complete the marker.
val javalinOlder = listOf("4.6.8")

fun resolvable(name: String): Configuration =
    configurations.create(name) {
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        }
    }

fun configurationName(
    framework: String,
    version: String,
) = "scenario-$framework-$version"

val scenarioConfigurations: Map<String, Configuration> =
    scenarioArtifacts
        .flatMap { (framework, releases) ->
            releases.map { (version, artifacts) ->
                val configuration = resolvable(configurationName(framework, version))
                artifacts.forEach { dependencies.add(configuration.name, it) }
                "$framework:$version" to configuration
            }
        }.toMap()

val javalinCoveredConfigurations: Map<String, Configuration> =
    javalinCovered.associateWith { version ->
        resolvable("javalin-covered-$version").also { dependencies.add(it.name, "io.javalin:javalin:$version") }
    }

val javalinOlderConfigurations: Map<String, Configuration> =
    javalinOlder.associateWith { version ->
        resolvable(configurationName("javalin-older", version)).also {
            dependencies.add(it.name, "io.javalin:javalin:$version")
            dependencies.add(it.name, "org.slf4j:slf4j-nop:$slf4jVersion")
        }
    }

dependencies {
    compileOnly("io.grpc:grpc-core:1.85.0")
    compileOnly("io.grpc:grpc-stub:1.85.0")
    compileOnly("com.linecorp.armeria:armeria:1.42.0")
    compileOnly("io.undertow:undertow-core:2.4.1.Final")
    compileOnly("io.javalin:javalin:5.6.5")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
    compileOnly("org.springframework:spring-webflux:7.0.9")
    compileOnly("org.springframework:spring-context:7.0.9")
    compileOnly("io.projectreactor.netty:reactor-netty-http:1.3.7")

    testImplementation(kotlin("test"))
    testImplementation(project(":"))
    testImplementation(project(":wire"))
}

kotlin {
    jvmToolchain(21)
}

tasks.compileJava {
    options.release.set(17)
}

val shadedAgentJar = project(":").tasks.named<AbstractArchiveTask>("shadowJar")

tasks.test {
    useJUnitPlatform()
    dependsOn(shadedAgentJar)
    inputs.file(shadedAgentJar.flatMap { it.archiveFile }).withPropertyName("shadedAgentJar")
    inputs.files(scenarioConfigurations.values).withPropertyName("scenarioClasspaths")
    inputs.files(javalinCoveredConfigurations.values).withPropertyName("javalinCoveredJars")
    inputs.files(javalinOlderConfigurations.values).withPropertyName("javalinOlderClasspaths")
    inputs.files(sourceSets.main.get().output).withPropertyName("mains")
    doFirst {
        layout.buildDirectory
            .file("reports/framework-markers.txt")
            .get()
            .asFile
            .delete()
        systemProperty(
            "otherlode.agent.shadedJar",
            shadedAgentJar
                .get()
                .archiveFile
                .get()
                .asFile.absolutePath,
        )
        systemProperty(
            "markers.mains",
            sourceSets.main
                .get()
                .output.classesDirs.asPath,
        )
        for ((key, configuration) in scenarioConfigurations) {
            systemProperty("markers.classpath.$key", configuration.asPath)
        }
        for ((version, configuration) in javalinCoveredConfigurations) {
            systemProperty("markers.javalinCovered.$version", configuration.asPath)
        }
        systemProperty("markers.releases", scenarioConfigurations.keys.joinToString(","))
        systemProperty("markers.javalinCovered", javalinCovered.joinToString(","))
        for ((version, configuration) in javalinOlderConfigurations) {
            systemProperty("markers.classpath.javalin-older:$version", configuration.asPath)
        }
        systemProperty("markers.javalinOlder", javalinOlder.joinToString(","))
    }
}

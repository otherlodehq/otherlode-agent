// The fixtures that prove each framework marker (ADR 0072): per framework and release, a JVM that
// uses only the framework's client side or no routing, one that configures its routing without
// serving, and one that serves a request, each run with -Xlog:class+load. Nothing here ships. The
// small programs in src/main/java are compiled against one release of each framework and run
// against every release listed below. Helidon 4 is compiled for Java 21, so the programs are compiled
// for 21 and the forked JVMs run on this project's 21 toolchain.
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.util.Properties

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
        "play-pekko" to
            listOf("3.0.12", "3.0.0").associateWith { v ->
                listOf(
                    "org.playframework:play_2.13:$v",
                    "org.playframework:play-java_2.13:$v",
                    "org.playframework:play-server_2.13:$v",
                    "org.playframework:play-pekko-http-server_2.13:$v",
                    "org.slf4j:slf4j-nop:$slf4jVersion",
                )
            },
        "play-akka" to
            listOf("2.9.12", "2.9.0", "2.8.22", "2.8.0").associateWith { v ->
                listOf(
                    "com.typesafe.play:play_2.13:$v",
                    "com.typesafe.play:play-java_2.13:$v",
                    "com.typesafe.play:play-server_2.13:$v",
                    "com.typesafe.play:play-akka-http-server_2.13:$v",
                    "org.slf4j:slf4j-nop:$slf4jVersion",
                )
            },
        "play-netty" to
            listOf("3.0.12", "2.9.12", "2.8.22").associateWith { v ->
                val group = if (v.startsWith("3.")) "org.playframework" else "com.typesafe.play"
                listOf(
                    "$group:play_2.13:$v",
                    "$group:play-java_2.13:$v",
                    "$group:play-server_2.13:$v",
                    "$group:play-netty-server_2.13:$v",
                    "org.slf4j:slf4j-nop:$slf4jVersion",
                )
            },
        "helidon" to
            listOf("4.5.5", "4.2.7", "4.0.0").associateWith { v ->
                listOf(
                    "io.helidon.webserver:helidon-webserver:$v",
                    "io.helidon.webclient:helidon-webclient:$v",
                )
            },
        "grails" to
            listOf("8.0.0", "7.2.4", "7.0.0", "6.2.3").associateWith { v ->
                if (v.startsWith("6.")) {
                    listOf(
                        "platform:org.grails:grails-bom:$v",
                        "org.grails:grails-web-boot:$v",
                        "org.grails:grails-plugin-controllers:$v",
                        "org.grails:grails-plugin-url-mappings:$v",
                        "org.springframework.boot:spring-boot-starter-tomcat:2.7.18",
                        "ch.qos.logback:logback-classic:1.2.13",
                    )
                } else {
                    listOf(
                        "platform:org.apache.grails:grails-bom:$v",
                        "org.apache.grails:grails-web-boot:$v",
                        "org.apache.grails:grails-controllers:$v",
                        "org.apache.grails:grails-url-mappings:$v",
                        "org.springframework.boot:spring-boot-starter-tomcat",
                        "ch.qos.logback:logback-classic",
                    )
                }
            },
        "struts" to
            listOf("7.4.0", "7.0.0", "6.12.0", "6.0.0", "2.5.33", "2.3.37").associateWith { v ->
                val tomcat = if (v.startsWith("7.")) "10.1.61" else "9.0.123"
                listOf(
                    "org.apache.struts:struts2-core:$v",
                    "org.apache.tomcat.embed:tomcat-embed-core:$tomcat",
                    "org.apache.tomcat.embed:tomcat-embed-jasper:$tomcat",
                    "org.apache.logging.log4j:log4j-core:2.24.3",
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
                artifacts.forEach {
                    dependencies.add(
                        configuration.name,
                        if (it.startsWith("platform:")) dependencies.platform(it.removePrefix("platform:")) else it,
                    )
                }
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
    compileOnly("org.playframework:play-java_2.13:3.0.12")
    compileOnly("org.playframework:play-server_2.13:3.0.12")
    compileOnly("io.helidon.webserver:helidon-webserver:4.5.5")
    compileOnly("io.helidon.webclient:helidon-webclient:4.5.5")
    compileOnly("org.apache.tomcat.embed:tomcat-embed-core:10.1.61")
    compileOnly(platform("org.apache.grails:grails-bom:7.2.4"))
    compileOnly("org.apache.grails:grails-web-boot:7.2.4")
    compileOnly("org.apache.grails:grails-controllers:7.2.4")
    compileOnly("org.springframework.boot:spring-boot-starter-web")

    testImplementation(kotlin("test"))
    testImplementation(project(":"))
    testImplementation(project(":wire"))
}

kotlin {
    jvmToolchain(21)
}

tasks.compileJava {
    options.release.set(21)
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
        val paths = Properties()
        paths["markers.mains"] =
            (
                sourceSets.main
                    .get()
                    .output.classesDirs +
                    files(
                        sourceSets.main
                            .get()
                            .output.resourcesDir,
                    )
            ).asPath
        for ((key, configuration) in scenarioConfigurations) {
            paths["markers.classpath.$key"] = configuration.asPath
        }
        for ((version, configuration) in javalinCoveredConfigurations) {
            paths["markers.javalinCovered.$version"] = configuration.asPath
        }
        for ((version, configuration) in javalinOlderConfigurations) {
            paths["markers.classpath.javalin-older:$version"] = configuration.asPath
        }
        val pathsFile =
            layout.buildDirectory
                .file("markers-paths.properties")
                .get()
                .asFile
        pathsFile.parentFile.mkdirs()
        pathsFile.outputStream().use { paths.store(it, null) }
        systemProperty("markers.paths", pathsFile.absolutePath)
        systemProperty("markers.releases", scenarioConfigurations.keys.joinToString(","))
        systemProperty("markers.javalinCovered", javalinCovered.joinToString(","))
        systemProperty("markers.javalinOlder", javalinOlder.joinToString(","))
    }
}

plugins {
    kotlin("jvm") version "2.2.21"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    implementation("org.testcontainers:testcontainers")
    implementation("org.testcontainers:testcontainers-postgresql")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val agentJar = providers.gradleProperty("agentJar")
val collectorDir = providers.gradleProperty("collectorDir")

tasks.test {
    // The macro run needs both inputs. With neither, only the unit tests run, so the harness's own
    // logic can be checked without Docker. With one, the build stops rather than guess.
    val macro = agentJar.isPresent || collectorDir.isPresent
    useJUnitPlatform {
        if (!macro) excludeTags("macro")
    }
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    doFirst {
        if (macro && !(agentJar.isPresent && collectorDir.isPresent)) {
            throw GradleException(
                "The macro run needs both -PagentJar=<path to otherlode-agent-*.jar> and " +
                    "-PcollectorDir=<local clone of otherlode-collector>.",
            )
        }
        if (!macro) {
            logger.lifecycle("No -PagentJar or -PcollectorDir: running the unit tests only.")
        }
    }

    val results =
        layout.buildDirectory
            .dir("results")
            .get()
            .asFile
    systemProperty("bench.resultsDir", results.absolutePath)
    systemProperty("bench.projectDir", projectDir.absolutePath)
    systemProperty("bench.agentRepoDir", projectDir.parentFile.absolutePath)
    // A relative path resolves against this directory, not the one Gradle was started from: `-p`
    // makes this directory Gradle's own start directory. The harness checks both paths exist.
    if (agentJar.isPresent) systemProperty("bench.agentJar", file(agentJar.get()).absolutePath)
    if (collectorDir.isPresent) systemProperty("bench.collectorDir", file(collectorDir.get()).absolutePath)
    systemProperty("bench.config", providers.gradleProperty("config").orElse("headline").get())
    systemProperty("bench.repeats", providers.gradleProperty("repeats").orElse("6").get())
    systemProperty("bench.warmupSeconds", providers.gradleProperty("warmupSeconds").orElse("60").get())
    systemProperty("bench.windowSeconds", providers.gradleProperty("windowSeconds").orElse("180").get())
}

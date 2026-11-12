import org.gradle.process.CommandLineArgumentProvider

plugins {
    kotlin("jvm") version "2.2.21"
    `jvm-test-suite`
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

val kotlinVersion = "2.2.21"
val byteBuddyVersion = "1.18.12"
val ktorVersion = "3.0.3"

// The framework canary sets this to the newest 3.x. compileOnly stays on ktorVersion, the 3.0.3 floor.
val ktorTestVersion = providers.gradleProperty("otherlode.framework.ktor3").getOrElse("3.5.2")

dependencies {
    implementation(project(":endpoints-api"))

    // Compile-time only: at runtime OtherlodeEndpoints comes from the target JVM's bootstrap classloader.
    compileOnly(project(":bootstrap"))

    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    // The oldest supported release. Never `implementation`: Ktor must not be shaded into the agent jar.
    compileOnly("io.ktor:ktor-server-core-jvm:$ktorVersion")

    testImplementation(project(":"))
    testImplementation(kotlin("test"))

    testImplementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    testImplementation("io.ktor:ktor-server-core-jvm:$ktorTestVersion")
    testImplementation("io.ktor:ktor-server-cio-jvm:$ktorTestVersion")
}

kotlin {
    jvmToolchain(21)
}

// Ktor3TestAgent must be a real -javaagent, not a self-attach: see the comment on it.
val ktor3TestAgentJar =
    tasks.register<Jar>("ktor3TestAgentJar") {
        archiveBaseName.set("ktor3-test-agent")
        destinationDirectory.set(layout.buildDirectory.dir("test-agent"))
        from(sourceSets.test.get().output)
        manifest {
            attributes["Premain-Class"] = "dev.otherlode.instrumentation.endpoints.ktor3.Ktor3TestAgent"
            attributes["Can-Retransform-Classes"] = "true"
        }
    }

tasks.test {
    dependsOn(ktor3TestAgentJar)
    useJUnitPlatform()
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-javaagent:${ktor3TestAgentJar.get().archiveFile.get().asFile.absolutePath}")
        },
    )
}

testing {
    suites {
        register<JvmTestSuite>("ktor3_0Test") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir("src/test/kotlin")
            }
            dependencies {
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
                implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
                implementation("io.ktor:ktor-server-core-jvm:3.0.3")
                implementation("io.ktor:ktor-server-cio-jvm:3.0.3")
            }
        }
    }
}

val ktor30TestAgentJar =
    tasks.register<Jar>("ktor30TestAgentJar") {
        archiveBaseName.set("ktor3-0-test-agent")
        destinationDirectory.set(layout.buildDirectory.dir("test-agent"))
        from(testing.suites.named<JvmTestSuite>("ktor3_0Test").map { it.sources.output })
        manifest {
            attributes["Premain-Class"] = "dev.otherlode.instrumentation.endpoints.ktor3.Ktor3TestAgent"
            attributes["Can-Retransform-Classes"] = "true"
        }
    }

testing.suites.named<JvmTestSuite>("ktor3_0Test") {
    targets {
        all {
            testTask.configure {
                dependsOn(ktor30TestAgentJar)
                jvmArgumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf("-javaagent:${ktor30TestAgentJar.get().archiveFile.get().asFile.absolutePath}")
                    },
                )
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("ktor3_0Test"))
}

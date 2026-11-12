import org.gradle.process.CommandLineArgumentProvider

plugins {
    kotlin("jvm") version "2.2.21"
    `jvm-test-suite`
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

// The framework canary sets these to the newest release of each line.
val jersey3Version = providers.gradleProperty("otherlode.framework.jersey3").getOrElse("3.1.12")
val jersey4Version = providers.gradleProperty("otherlode.framework.jersey4").getOrElse("4.0.3")
val jersey2Version = providers.gradleProperty("otherlode.framework.jersey2").getOrElse("2.48")

val resteasyReactiveVersion = providers.gradleProperty("otherlode.framework.resteasyReactive").getOrElse("3.40.1")

val resteasyReactiveServer by configurations.creating { isTransitive = false }
val resteasyReactiveCommon by configurations.creating { isTransitive = false }

dependencies {
    resteasyReactiveServer("io.quarkus.resteasy.reactive:resteasy-reactive:$resteasyReactiveVersion")
    resteasyReactiveCommon("io.quarkus.resteasy.reactive:resteasy-reactive-common:$resteasyReactiveVersion")

    implementation(project(":endpoints-api"))

    // Compile-time only: at runtime OtherlodeEndpoints comes from the target JVM's bootstrap classloader.
    compileOnly(project(":bootstrap"))

    compileOnly("net.bytebuddy:byte-buddy:1.18.12")

    testImplementation(project(":"))
    testImplementation(kotlin("test"))

    testImplementation("net.bytebuddy:byte-buddy:1.18.12")

    testImplementation("org.glassfish.jersey.containers:jersey-container-jdk-http:$jersey3Version")
    testImplementation("org.glassfish.jersey.inject:jersey-hk2:$jersey3Version")
    testImplementation("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
}

kotlin {
    jvmToolchain(21)
}

// JaxRsTestAgent must be a real -javaagent: JUnit discovery loads the fixture resource classes
// before any test method runs.
val jaxRsTestAgentJar =
    tasks.register<Jar>("jaxRsTestAgentJar") {
        archiveBaseName.set("jaxrs-test-agent")
        destinationDirectory.set(layout.buildDirectory.dir("test-agent"))
        from(sourceSets.test.get().output)
        manifest {
            attributes["Premain-Class"] = "dev.otherlode.instrumentation.endpoints.jaxrs.JaxRsTestAgent"
            attributes["Can-Retransform-Classes"] = "true"
        }
    }

// On a test classpath the RESTEasy Reactive server jar would open the inheritance gate for every
// test that sets jerseyPresent to false.
val resteasyReactiveJarProperties =
    CommandLineArgumentProvider {
        listOf(
            "-Dotherlode.test.resteasyReactiveServerJar=${resteasyReactiveServer.singleFile.absolutePath}",
            "-Dotherlode.test.resteasyReactiveCommonJar=${resteasyReactiveCommon.singleFile.absolutePath}",
        )
    }

tasks.test {
    dependsOn(jaxRsTestAgentJar)
    useJUnitPlatform()
    jvmArgumentProviders.add(resteasyReactiveJarProperties)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-javaagent:${jaxRsTestAgentJar.get().archiveFile.get().asFile.absolutePath}")
        },
    )
}

testing {
    suites {
        register<JvmTestSuite>("javaxTest") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir("src/javaxTest/kotlin")
            }
            dependencies {
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.21")
                implementation("net.bytebuddy:byte-buddy:1.18.12")
                implementation("org.glassfish.jersey.containers:jersey-container-jdk-http:$jersey2Version")
                implementation("org.glassfish.jersey.inject:jersey-hk2:$jersey2Version")
                implementation("javax.ws.rs:javax.ws.rs-api:2.1.1")
            }
        }

        register<JvmTestSuite>("jersey4Test") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir("src/test/kotlin")
            }
            dependencies {
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.21")
                implementation("net.bytebuddy:byte-buddy:1.18.12")
                implementation("org.glassfish.jersey.containers:jersey-container-jdk-http:$jersey4Version")
                implementation("org.glassfish.jersey.inject:jersey-hk2:$jersey4Version")
                implementation("jakarta.ws.rs:jakarta.ws.rs-api:4.0.0")
            }
        }
    }
}

val javaxTestAgentJar =
    tasks.register<Jar>("javaxTestAgentJar") {
        archiveBaseName.set("jaxrs-javax-test-agent")
        destinationDirectory.set(layout.buildDirectory.dir("test-agent"))
        from(testing.suites.named<JvmTestSuite>("javaxTest").map { it.sources.output })
        manifest {
            attributes["Premain-Class"] = "dev.otherlode.instrumentation.endpoints.jaxrs.JaxRsTestAgent"
            attributes["Can-Retransform-Classes"] = "true"
        }
    }

testing.suites.named<JvmTestSuite>("javaxTest") {
    targets {
        all {
            testTask.configure {
                dependsOn(javaxTestAgentJar)
                jvmArgumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf("-javaagent:${javaxTestAgentJar.get().archiveFile.get().asFile.absolutePath}")
                    },
                )
            }
        }
    }
}

val jersey4TestAgentJar =
    tasks.register<Jar>("jersey4TestAgentJar") {
        archiveBaseName.set("jaxrs-jersey4-test-agent")
        destinationDirectory.set(layout.buildDirectory.dir("test-agent"))
        from(testing.suites.named<JvmTestSuite>("jersey4Test").map { it.sources.output })
        manifest {
            attributes["Premain-Class"] = "dev.otherlode.instrumentation.endpoints.jaxrs.JaxRsTestAgent"
            attributes["Can-Retransform-Classes"] = "true"
        }
    }

testing.suites.named<JvmTestSuite>("jersey4Test") {
    targets {
        all {
            testTask.configure {
                dependsOn(jersey4TestAgentJar)
                jvmArgumentProviders.add(resteasyReactiveJarProperties)
                jvmArgumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf("-javaagent:${jersey4TestAgentJar.get().archiveFile.get().asFile.absolutePath}")
                    },
                )
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("javaxTest"))
    dependsOn(testing.suites.named("jersey4Test"))
}

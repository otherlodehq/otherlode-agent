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
val coroutinesVersion = "1.10.2"

// The Spring Framework release each test suite runs against, one property per line. The weekly
// framework canary sets them to the newest release of the line; the compile-against floor
// (5.3.39 below) is not one of them. The properties are the ones :endpoints-spring-webmvc reads.
val spring53Version = providers.gradleProperty("otherlode.framework.spring53").getOrElse("5.3.39")
val spring6Version = providers.gradleProperty("otherlode.framework.spring6").getOrElse("6.2.19")
val spring7Version = providers.gradleProperty("otherlode.framework.spring7").getOrElse("7.0.9")

dependencies {
    implementation(project(":endpoints-api"))
    implementation(project(":endpoints-spring-common"))

    // Compile-time only: at runtime OtherlodeEndpoints comes from the target JVM's bootstrap
    // classloader, where BootstrapHolder appends the embedded jar (see the root project's
    // build.gradle.kts for the full rationale).
    compileOnly(project(":bootstrap"))

    // :endpoints-api depends on byte-buddy as `implementation`, which is never transitive, so it
    // does not put ByteBuddy on this module's own compile classpath. Needed here directly for
    // net.bytebuddy.asm.Advice (the Java advice classes) and for the EndpointModule types this
    // module's own Kotlin code is written against. compileOnly: at runtime, ByteBuddy comes from
    // the root project's own dependency once this module's classes are merged into the shaded
    // agent jar.
    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    // Compiled against the oldest supported Spring Framework version, per ADR 0017: a module
    // builds against its floor version and disables itself on a LinkageError against a newer or
    // older one it does not actually match. Never `implementation`: Spring must never be shaded
    // into the agent jar, since it belongs to the target application, not to this agent. Reactor
    // arrives transitively, for the advice that wraps a resource lookup's Mono.
    compileOnly("org.springframework:spring-webflux:5.3.39")
    compileOnly("org.springframework:spring-context:5.3.39")

    testImplementation(project(":"))
    testImplementation(kotlin("test"))

    // Lets the test self-attach with ByteBuddyAgent.install() and weave real advice into Spring's
    // own classes.
    testImplementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
    testImplementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    // The default test suite runs against Spring Framework 6.x, driven through
    // WebTestClient.bindToApplicationContext, which runs a real DispatcherHandler so both the
    // registration and dispatch hooks fire without a server.
    testImplementation("org.springframework:spring-webflux:$spring6Version")
    testImplementation("org.springframework:spring-context:$spring6Version")
    testImplementation("org.springframework:spring-test:$spring6Version")

    // The Kotlin DSL's coRouter, whose handlers Spring wraps in its own class.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$coroutinesVersion")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
}

// A second and a third JVM Test Suite run the same test sources (src/test/kotlin) against Spring
// Framework 7.x and 5.3.x, each in its own forked test JVM with its own classpath, so the one
// SpringWebFluxModule this module ships is proven against every supported major version without
// a per-version module or a per-version copy of the tests.
testing {
    suites {
        register<JvmTestSuite>("spring7Test") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir("src/test/kotlin")
            }
            dependencies {
                // Unlike the built-in `test` suite, a suite registered here has no implicit
                // dependency on this same project's own production code.
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
                implementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
                implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
                implementation("org.springframework:spring-webflux:$spring7Version")
                implementation("org.springframework:spring-context:$spring7Version")
                implementation("org.springframework:spring-test:$spring7Version")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$coroutinesVersion")
            }
            targets {
                all {
                    testTask.configure {
                        jvmArgs("-Djdk.attach.allowAttachSelf=true")
                    }
                }
            }
        }

        register<JvmTestSuite>("spring53Test") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir("src/test/kotlin")
            }
            dependencies {
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
                implementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
                implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
                implementation("org.springframework:spring-webflux:$spring53Version")
                implementation("org.springframework:spring-context:$spring53Version")
                implementation("org.springframework:spring-test:$spring53Version")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$coroutinesVersion")
            }
            targets {
                all {
                    testTask.configure {
                        jvmArgs("-Djdk.attach.allowAttachSelf=true")
                    }
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("spring7Test"))
    dependsOn(testing.suites.named("spring53Test"))
}

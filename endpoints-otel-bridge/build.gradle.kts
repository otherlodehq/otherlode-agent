import org.gradle.process.CommandLineArgumentProvider

plugins {
    kotlin("jvm") version "2.2.21"
    `jvm-test-suite`
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

// The oldest 2.x release of the unshaded artifact: instrumentation-api 2.0.0 already carries
// HttpServerAttributesExtractor.onEnd and internal.HttpRouteState at the names this module's
// advice is written against (see OnEndAdvice's own KDoc for what was verified and how). Compiling
// against the floor version, never a newer one, is this project's no-muzzle policy: a module
// disables itself once on a LinkageError against a version its advice does not match, rather than
// pinning to whatever is newest today.
val openTelemetryInstrumentationApiVersion = "2.0.0"

// A newer release, matched to the OpenTelemetry SDK versions below, for the module's own tests:
// they drive a real Instrumenter end to end, which needs more of the API surface than the advice
// class itself touches.
// The weekly framework canary sets both to the newest release of their lines.
val openTelemetryInstrumentationApiTestVersion = providers.gradleProperty("otherlode.framework.otelInstrumentationApi").getOrElse("2.31.1")
val openTelemetrySdkVersion = providers.gradleProperty("otherlode.framework.otelSdk").getOrElse("1.65.0")

dependencies {
    implementation(project(":endpoints-api"))

    // Compile-time only: at runtime OtherlodeEndpoints comes from the target JVM's bootstrap
    // classloader, where BootstrapHolder appends the embedded jar (see the root project's
    // build.gradle.kts for the full rationale).
    compileOnly(project(":bootstrap"))

    // :endpoints-api depends on byte-buddy as `implementation`, which is never transitive, so it
    // does not put ByteBuddy on this module's own compile classpath. Needed here directly for
    // net.bytebuddy.asm.Advice (the Java advice class) and for the EndpointModule types
    // (TypeDescription, DynamicType.Builder, ElementMatcher) this module's own Kotlin code is
    // written against. compileOnly: at runtime, ByteBuddy comes from the root project's own
    // dependency once this module's classes are merged into the shaded agent jar.
    compileOnly("net.bytebuddy:byte-buddy:1.18.12")

    // Never shaded into the agent jar: OpenTelemetry, when present at all, belongs to the target
    // application or to the separate OpenTelemetry javaagent, never to this one. OnEndAdvice's own
    // bytecode is inlined directly into whichever copy is present at the woven call site; this
    // dependency only lets javac resolve its signature.
    compileOnly("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api:$openTelemetryInstrumentationApiVersion")

    // Gives EndpointRegistry, EndpointInstrumentation, and BootstrapHolder to the integration
    // test below, the same way :endpoints-jdk-httpserver's own tests depend on the root project.
    testImplementation(project(":"))
    testImplementation(kotlin("test"))

    // Lets the test self-attach with ByteBuddyAgent.install() and weave real advice into a real
    // OpenTelemetry Instrumenter's own classes, the same way :endpoints-jdk-httpserver's test does
    // for the JDK's HttpServer.
    testImplementation("net.bytebuddy:byte-buddy-agent:1.18.12")
    testImplementation("net.bytebuddy:byte-buddy:1.18.12")

    // A newer instrumentation-api release than the advice class compiles against, plus a plain
    // OpenTelemetry SDK (no OpenTelemetryExtension convenience) so the test builds and drives a
    // real Instrumenter, ending real spans an InMemorySpanExporter captures.
    testImplementation("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api:$openTelemetryInstrumentationApiTestVersion")
    testImplementation("io.opentelemetry:opentelemetry-sdk:$openTelemetrySdkVersion")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:$openTelemetrySdkVersion")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
}

val tomcatVersion = providers.gradleProperty("otherlode.framework.tomcat10").getOrElse("10.1.61")
val springVersion = providers.gradleProperty("otherlode.framework.spring6").getOrElse("6.2.19")
val jerseyVersion = providers.gradleProperty("otherlode.framework.jersey3").getOrElse("3.1.12")

val otelJavaagent by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}

dependencies {
    otelJavaagent("io.opentelemetry.javaagent:opentelemetry-javaagent:$openTelemetryInstrumentationApiTestVersion")
}

// The two suites below run a real servlet container with OpenTelemetry's own instrumentation, in a
// JVM of their own per test class, since the endpoint registry is one per JVM. PrefixTestAgent is
// the agent on that command line. The jar packages the suite's compiled output; everything its
// premain references resolves off the test JVM's ordinary classpath.
fun prefixTestAgentJar(
    name: String,
    output: Provider<out FileCollection>,
) = tasks.register<Jar>("${name}AgentJar") {
    archiveBaseName.set("otel-bridge-$name-agent")
    destinationDirectory.set(layout.buildDirectory.dir("test-agent"))
    from(output)
    manifest {
        attributes["Premain-Class"] = "dev.otherlode.instrumentation.endpoints.otelbridge.PrefixTestAgent"
        attributes["Can-Retransform-Classes"] = "true"
    }
}

testing {
    suites {
        register<JvmTestSuite>("otelAgentTest") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir("src/prefixTestShared/kotlin")
                resources.srcDir("src/prefixTestShared/resources")
                kotlin.srcDir("src/agentOrderShared/kotlin")
            }
            dependencies {
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                compileOnly(project(":bootstrap"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.21")
                implementation("net.bytebuddy:byte-buddy:1.18.12")
                implementation("org.apache.tomcat.embed:tomcat-embed-core:$tomcatVersion")
                implementation("org.springframework:spring-webmvc:$springVersion")
                implementation("org.glassfish.jersey.containers:jersey-container-servlet:$jerseyVersion")
                implementation("org.glassfish.jersey.inject:jersey-hk2:$jerseyVersion")
                implementation("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
            }
        }

        register<JvmTestSuite>("otelAgentFirstTest") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir("src/prefixTestShared/kotlin")
                resources.srcDir("src/prefixTestShared/resources")
                kotlin.srcDir("src/agentOrderShared/kotlin")
            }
            dependencies {
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                compileOnly(project(":bootstrap"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.21")
                implementation("net.bytebuddy:byte-buddy:1.18.12")
                implementation("org.apache.tomcat.embed:tomcat-embed-core:$tomcatVersion")
                implementation("org.springframework:spring-webmvc:$springVersion")
                implementation("org.glassfish.jersey.containers:jersey-container-servlet:$jerseyVersion")
                implementation("org.glassfish.jersey.inject:jersey-hk2:$jerseyVersion")
                implementation("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
            }
        }

        register<JvmTestSuite>("otelLibraryTest") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir("src/prefixTestShared/kotlin")
                resources.srcDir("src/prefixTestShared/resources")
            }
            dependencies {
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                compileOnly(project(":bootstrap"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.21")
                implementation("net.bytebuddy:byte-buddy:1.18.12")
                implementation("org.apache.tomcat.embed:tomcat-embed-core:$tomcatVersion")
                implementation("org.springframework:spring-webmvc:$springVersion")
                implementation("io.opentelemetry.instrumentation:opentelemetry-spring-webmvc-6.0:$openTelemetryInstrumentationApiTestVersion-alpha")
                implementation("io.opentelemetry:opentelemetry-sdk:$openTelemetrySdkVersion")
            }
        }
    }
}

val otelAgentTestAgentJar =
    prefixTestAgentJar("agent", provider { testing.suites.named<JvmTestSuite>("otelAgentTest").get().sources.output })
val otelAgentFirstTestAgentJar =
    prefixTestAgentJar("agent-first", provider { testing.suites.named<JvmTestSuite>("otelAgentFirstTest").get().sources.output })
val otelLibraryTestAgentJar =
    prefixTestAgentJar("library", provider { testing.suites.named<JvmTestSuite>("otelLibraryTest").get().sources.output })

testing.suites.named<JvmTestSuite>("otelAgentTest") {
    targets {
        all {
            testTask.configure {
                dependsOn(otelAgentTestAgentJar)
                include("**/*Test.class")
                forkEvery = 1
                jvmArgumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf(
                            "-javaagent:${otelAgentTestAgentJar.get().archiveFile.get().asFile.absolutePath}",
                            "-javaagent:${otelJavaagent.singleFile.absolutePath}",
                            "-Dotel.traces.exporter=none",
                            "-Dotel.metrics.exporter=none",
                            "-Dotel.logs.exporter=none",
                        )
                    },
                )
            }
        }
    }
}

// OpenTelemetry's agent listed first, so it has rewritten framework classes before this agent sees
// them; otelAgentTest lists this agent first.
testing.suites.named<JvmTestSuite>("otelAgentFirstTest") {
    targets {
        all {
            testTask.configure {
                dependsOn(otelAgentFirstTestAgentJar)
                include("**/*Test.class")
                forkEvery = 1
                jvmArgumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf(
                            "-javaagent:${otelJavaagent.singleFile.absolutePath}",
                            "-javaagent:${otelAgentFirstTestAgentJar.get().archiveFile.get().asFile.absolutePath}",
                            "-Dotel.traces.exporter=none",
                            "-Dotel.metrics.exporter=none",
                            "-Dotel.logs.exporter=none",
                        )
                    },
                )
            }
        }
    }
}

testing.suites.named<JvmTestSuite>("otelLibraryTest") {
    targets {
        all {
            testTask.configure {
                dependsOn(otelLibraryTestAgentJar)
                include("**/*Test.class")
                forkEvery = 1
                jvmArgumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf("-javaagent:${otelLibraryTestAgentJar.get().archiveFile.get().asFile.absolutePath}")
                    },
                )
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("otelAgentTest"))
    dependsOn(testing.suites.named("otelAgentFirstTest"))
    dependsOn(testing.suites.named("otelLibraryTest"))
}

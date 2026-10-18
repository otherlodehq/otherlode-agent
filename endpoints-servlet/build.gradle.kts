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

// The container release each test suite runs against, one property per line. The weekly framework
// canary sets them to the newest release of the line; the compile-against floor (the Servlet 4.0
// API below) is not one of them.
val tomcat9Version = providers.gradleProperty("otherlode.framework.tomcat9").getOrElse("9.0.123")
val tomcat10Version = providers.gradleProperty("otherlode.framework.tomcat10").getOrElse("10.1.61")

dependencies {
    implementation(project(":endpoints-api"))

    // Compile-time only: at runtime OtherlodeEndpoints comes from the target JVM's bootstrap
    // classloader, where BootstrapHolder appends the embedded jar (see the root project's
    // build.gradle.kts for the full rationale).
    compileOnly(project(":bootstrap"))

    // Needed directly for net.bytebuddy.asm.Advice (the Java advice class), for the EndpointModule
    // types and for the ASM the class-file reads use. compileOnly: at runtime, ByteBuddy comes from
    // the root project's own dependency once this module's classes are merged into the shaded
    // agent jar.
    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    // Compiled against the oldest supported servlet API, per ADR 0017 and ADR 0074: Servlet 4.0 is
    // the first to have HttpServletRequest.getHttpServletMapping. The advice is rebound to
    // jakarta.servlet where the woven method takes that namespace. Never `implementation`: the
    // servlet API belongs to the target application and must not be shaded into the agent jar.
    compileOnly("javax.servlet:javax.servlet-api:4.0.1")

    testImplementation(project(":"))
    testCompileOnly(project(":bootstrap"))
    testImplementation(kotlin("test"))
    testImplementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
    testImplementation("org.apache.tomcat.embed:tomcat-embed-core:$tomcat10Version")
    testImplementation("org.apache.tomcat.embed:tomcat-embed-jasper:$tomcat10Version")
}

kotlin {
    jvmToolchain(21)
}

// The tests are written once against jakarta.servlet. A suite for a javax container compiles a copy
// of them with the namespace rewritten, so adding a container is a dependency list and a suite.
val javaxTestSources =
    tasks.register<Sync>("javaxTestSources") {
        from("src/test/kotlin")
        into(layout.buildDirectory.dir("generated/javaxTest/kotlin"))
        filter { line -> line.replace("jakarta.servlet", "javax.servlet").replace("jakarta/servlet", "javax/servlet") }
    }

// ServletTestAgent is installed as a real -javaagent on the test JVM's own command line, as
// JaxRsTestAgent is: the container loads HttpServlet before any test method runs, and the weave
// has to come first. The jar packages the suite's compiled test output; everything the agent's
// premain references resolves off the test JVM's ordinary classpath.
fun Project.servletTestAgentJar(
    name: String,
    output: Provider<out FileCollection>,
) = tasks.register<Jar>("${name}AgentJar") {
    archiveBaseName.set("servlet-$name-agent")
    destinationDirectory.set(layout.buildDirectory.dir("test-agent"))
    from(output)
    manifest {
        attributes["Premain-Class"] = "dev.otherlode.instrumentation.endpoints.servlet.ServletTestAgent"
        attributes["Can-Retransform-Classes"] = "true"
    }
}

fun agentArguments(jar: TaskProvider<Jar>) =
    CommandLineArgumentProvider { listOf("-javaagent:${jar.get().archiveFile.get().asFile.absolutePath}") }

// The module disables itself process-wide, and the registry is one per JVM, so each test class
// runs in its own JVM.
val tomcat10AgentJar = servletTestAgentJar("tomcat10", provider { sourceSets.test.get().output })

tasks.test {
    dependsOn(tomcat10AgentJar)
    useJUnitPlatform()
    forkEvery = 1
    jvmArgumentProviders.add(agentArguments(tomcat10AgentJar))
}

testing {
    suites {
        register<JvmTestSuite>("tomcat9Test") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir(javaxTestSources.map { it.destinationDir })
            }
            dependencies {
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
                implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
                compileOnly(project(":bootstrap"))
                implementation("org.apache.tomcat.embed:tomcat-embed-core:$tomcat9Version")
                implementation("org.apache.tomcat.embed:tomcat-embed-jasper:$tomcat9Version")
            }
        }
    }
}

kotlin.target.compilations.getByName("tomcat9Test").associateWith(kotlin.target.compilations.getByName("main"))

val tomcat9AgentJar =
    servletTestAgentJar("tomcat9", provider { testing.suites.named<JvmTestSuite>("tomcat9Test").get().sources.output })

testing.suites.named<JvmTestSuite>("tomcat9Test") {
    targets {
        all {
            testTask.configure {
                dependsOn(tomcat9AgentJar)
                forkEvery = 1
                jvmArgumentProviders.add(agentArguments(tomcat9AgentJar))
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("tomcat9Test"))
}

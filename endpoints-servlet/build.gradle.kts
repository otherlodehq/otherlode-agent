import org.gradle.api.plugins.jvm.JvmComponentDependencies
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

// The framework canary sets these to the newest release of each line.
val tomcat9Version = providers.gradleProperty("otherlode.framework.tomcat9").getOrElse("9.0.123")
val tomcat10Version = providers.gradleProperty("otherlode.framework.tomcat10").getOrElse("10.1.61")
val tomcat11Version = providers.gradleProperty("otherlode.framework.tomcat11").getOrElse("11.0.27")
val jetty9Version = providers.gradleProperty("otherlode.framework.jetty9").getOrElse("9.4.58.v20250814")
val jetty10Version = providers.gradleProperty("otherlode.framework.jetty10").getOrElse("10.0.26")
val jetty12Version = providers.gradleProperty("otherlode.framework.jetty12").getOrElse("12.0.40")
val undertowVersion = providers.gradleProperty("otherlode.framework.undertow").getOrElse("2.3.26.Final")
val springBootVersion = providers.gradleProperty("otherlode.framework.springBoot3").getOrElse("3.5.16")
val h2Version = providers.gradleProperty("otherlode.framework.h2").getOrElse("2.3.232")

dependencies {
    implementation(project(":endpoints-api"))

    // Compile-time only: at runtime OtherlodeEndpoints comes from the target JVM's bootstrap classloader.
    compileOnly(project(":bootstrap"))

    // compileOnly: the root project ships the wire module in the agent jar.
    compileOnly(project(":wire"))

    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    // The oldest supported servlet API: Servlet 4.0 is the first to have getHttpServletMapping. Never
    // `implementation`: the servlet API must not be shaded into the agent jar.
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

sourceSets.test {
    kotlin.srcDir("src/tomcat/kotlin")
    kotlin.srcDir("src/shapes/kotlin")
}

// A test agent must be a real -javaagent: the container loads HttpServlet before any test method
// runs, and the weave has to come first.
fun servletTestAgentJar(
    name: String,
    agentClass: String,
    output: Provider<out FileCollection>,
) = tasks.register<Jar>("${name}AgentJar") {
    archiveBaseName.set("servlet-$name-agent")
    destinationDirectory.set(layout.buildDirectory.dir("test-agent"))
    from(output)
    manifest {
        attributes["Premain-Class"] = agentClass
        attributes["Can-Retransform-Classes"] = "true"
    }
}

fun agentArguments(jar: TaskProvider<Jar>) =
    CommandLineArgumentProvider { listOf("-javaagent:${jar.get().archiveFile.get().asFile.absolutePath}") }

val testAgent = "dev.otherlode.instrumentation.endpoints.servlet.ServletTestAgent"

// The module disables itself process-wide, and the registry is one per JVM, so each test class
// runs in its own JVM.
val tomcat10AgentJar = servletTestAgentJar("tomcat10", testAgent, provider { sourceSets.test.get().output })

tasks.test {
    dependsOn(tomcat10AgentJar)
    useJUnitPlatform()
    forkEvery = 1
    systemProperty("otherlode.servlet.patch", "false")
    jvmArgumentProviders.add(agentArguments(tomcat10AgentJar))
}

class SourceDir(
    val path: String,
    val rewrite: Boolean = false,
    val excludes: List<String> = emptyList(),
)

fun containerSuite(
    name: String,
    kotlinDirs: List<SourceDir>,
    javaDirs: List<SourceDir> = listOf(SourceDir("src/test/java", rewrite = true)),
    patch: Boolean = false,
    agentClass: String = testAgent,
    notation: JvmComponentDependencies.() -> Unit,
) {
    val suiteName = "${name}Test"
    val generated = layout.buildDirectory.dir("generated/$name")
    val needsCopy = kotlinDirs.any { it.rewrite } || javaDirs.any { it.rewrite }

    fun sync(
        taskName: String,
        dirs: List<SourceDir>,
        into: String,
    ) = tasks.register<Sync>(taskName) {
        into(generated.map { it.dir(into) })
        for (dir in dirs) {
            from(dir.path) {
                exclude(dir.excludes)
                if (dir.rewrite) {
                    filter { line -> line.replace("jakarta.servlet", "javax.servlet").replace("jakarta/servlet", "javax/servlet") }
                }
            }
        }
    }

    val kotlinSync = if (needsCopy) sync("${name}KotlinSources", kotlinDirs, "kotlin") else null
    val javaSync = if (needsCopy && javaDirs.isNotEmpty()) sync("${name}JavaSources", javaDirs, "java") else null

    testing.suites.register<JvmTestSuite>(suiteName) {
        useJUnitJupiter()
        sources {
            if (kotlinSync != null) kotlin.srcDir(kotlinSync.map { it.destinationDir }) else kotlinDirs.forEach { kotlin.srcDir(it.path) }
            if (javaSync != null) java.srcDir(javaSync.map { it.destinationDir }) else javaDirs.forEach { java.srcDir(it.path) }
        }
        dependencies {
            implementation(project())
            implementation(project(":"))
            implementation(project(":endpoints-api"))
            implementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
            implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
            compileOnly(project(":bootstrap"))
            notation(this)
        }
    }

    kotlin.target.compilations
        .getByName(suiteName)
        .associateWith(kotlin.target.compilations.getByName("main"))

    val agentJar =
        servletTestAgentJar(
            name,
            agentClass,
            provider {
                testing.suites
                    .named<JvmTestSuite>(suiteName)
                    .get()
                    .sources.output
            },
        )
    testing.suites.named<JvmTestSuite>(suiteName) {
        targets {
            all {
                testTask.configure {
                    dependsOn(agentJar)
                    forkEvery = 1
                    systemProperty("otherlode.servlet.patch", patch.toString())
                    jvmArgumentProviders.add(agentArguments(agentJar))
                }
            }
        }
    }
    tasks.check { dependsOn(testing.suites.named(suiteName)) }
}

val sharedTests = SourceDir("src/test/kotlin", rewrite = true)
val sharedJakartaTests = SourceDir("src/test/kotlin")
val shapesTests = SourceDir("src/shapes/kotlin", rewrite = true)

containerSuite(
    "tomcat9",
    listOf(sharedTests, SourceDir("src/tomcat/kotlin", rewrite = true), shapesTests),
) {
    implementation("org.apache.tomcat.embed:tomcat-embed-core:$tomcat9Version")
    implementation("org.apache.tomcat.embed:tomcat-embed-jasper:$tomcat9Version")
}

containerSuite(
    "tomcat11",
    listOf(sharedJakartaTests, SourceDir("src/tomcat/kotlin")),
    javaDirs = listOf(SourceDir("src/test/java")),
    patch = true,
) {
    implementation("org.apache.tomcat.embed:tomcat-embed-core:$tomcat11Version")
    implementation("org.apache.tomcat.embed:tomcat-embed-jasper:$tomcat11Version")
}

containerSuite(
    "jetty10",
    listOf(sharedTests, SourceDir("src/jetty/kotlin"), SourceDir("src/jetty10/kotlin")),
) {
    implementation("org.eclipse.jetty:jetty-servlet:$jetty10Version")
    implementation("org.eclipse.jetty:apache-jsp:$jetty10Version")
}

containerSuite(
    "jetty12",
    listOf(sharedJakartaTests, SourceDir("src/jetty12/kotlin")),
    javaDirs = listOf(SourceDir("src/test/java")),
) {
    implementation("org.eclipse.jetty.ee10:jetty-ee10-servlet:$jetty12Version")
    implementation("org.eclipse.jetty.ee10:jetty-ee10-apache-jsp:$jetty12Version")
}

containerSuite(
    "undertow",
    listOf(sharedJakartaTests, SourceDir("src/undertow/kotlin")),
    javaDirs = listOf(SourceDir("src/test/java")),
) {
    implementation("io.undertow:undertow-servlet:$undertowVersion")
}

// Jetty 9.4 implements Servlet 3.1, which has no getHttpServletMapping. Only what compiles against
// that API is shared: the container, the support and the agent.
containerSuite(
    "jetty9",
    listOf(
        SourceDir(
            "src/test/kotlin",
            rewrite = true,
            excludes = listOf("**/ServletModuleTest.kt", "**/ServletModuleDisablingTest.kt"),
        ),
        SourceDir("src/jetty/kotlin"),
        SourceDir("src/jetty9/kotlin"),
    ),
    javaDirs = emptyList(),
) {
    implementation("org.eclipse.jetty:jetty-servlet:$jetty9Version")
}

containerSuite(
    "jetty9Servlet4Api",
    listOf(
        SourceDir(
            "src/test/kotlin",
            rewrite = true,
            excludes = listOf("**/ServletModuleTest.kt", "**/ServletModuleDisablingTest.kt"),
        ),
        SourceDir("src/jetty/kotlin"),
        SourceDir("src/jetty9api4/kotlin"),
    ),
    javaDirs = emptyList(),
) {
    implementation("org.eclipse.jetty:jetty-servlet:$jetty9Version")
    implementation("javax.servlet:javax.servlet-api:4.0.1")
}

containerSuite(
    "springBoot",
    listOf(SourceDir("src/springboot/kotlin")),
    javaDirs = emptyList(),
    agentClass = "dev.otherlode.instrumentation.endpoints.servlet.BootTestAgent",
) {
    implementation(project(":endpoints-spring-common"))
    implementation(project(":endpoints-spring-webmvc"))
    implementation("org.springframework.boot:spring-boot-starter-web:$springBootVersion")
    implementation("com.h2database:h2:$h2Version")
}

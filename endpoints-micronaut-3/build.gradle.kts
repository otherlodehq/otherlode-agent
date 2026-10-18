plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("kapt")
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

val kotlinVersion = "2.2.21"
val byteBuddyVersion = "1.18.12"
val slf4jVersion = "2.0.16"
val coroutinesVersion = "1.9.0"

val micronaut3Version = providers.gradleProperty("otherlode.framework.micronaut3").getOrElse("3.10.15")
val micronaut3FloorVersion = "3.0.0"
val newestServletVersion = providers.gradleProperty("otherlode.framework.micronautServlet3").getOrElse("3.3.8")

fun jarsOnly(
    name: String,
    vararg coordinates: String,
) = configurations.create(name) {
    isCanBeConsumed = false
    isTransitive = false
    dependencies.addAll(coordinates.map { project.dependencies.create(it) })
}

// Class files read by the matcher test, never loaded: the Micronaut 3 classes it must accept at
// the floor, and the Micronaut 4 classes it must reject at the floor and at the newest tested.
val micronaut3FloorJars =
    jarsOnly(
        "micronaut3FloorJars",
        "io.micronaut:micronaut-router:$micronaut3FloorVersion",
        "io.micronaut:micronaut-http-server-netty:$micronaut3FloorVersion",
        "io.micronaut.servlet:micronaut-servlet-core:3.0.0",
        "io.micronaut.aws:micronaut-function-aws-api-proxy:3.0.0",
    )
val micronaut4FloorJars =
    jarsOnly(
        "micronaut4FloorJars",
        "io.micronaut:micronaut-router:4.0.0",
        "io.micronaut:micronaut-http-server-netty:4.0.0",
        "io.micronaut.servlet:micronaut-servlet-core:4.0.0",
    )
val micronaut4NewestJars =
    jarsOnly(
        "micronaut4NewestJars",
        "io.micronaut:micronaut-router:4.10.32",
        "io.micronaut:micronaut-http-server-netty:4.10.32",
        "io.micronaut.servlet:micronaut-servlet-core:4.10.1",
    )

dependencies {
    implementation(project(":endpoints-api"))
    implementation(project(":endpoints-micronaut-common"))

    compileOnly(project(":bootstrap"))
    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    // Compiled against the oldest supported release, per ADR 0017. Never `implementation`: Micronaut
    // belongs to the target application and must not be shaded into the agent jar.
    compileOnly("io.micronaut:micronaut-router:$micronaut3FloorVersion")
    compileOnly("io.micronaut:micronaut-http-server:$micronaut3FloorVersion")
}

kotlin {
    jvmToolchain(21)
}

// One test source set per Micronaut release and server under test. The controllers need Micronaut's
// annotation processor at the release they run on, so each set compiles the shared sources again
// with its own. `test` runs the newest tested 3.x on Netty, micronaut30Test the 3.0.0 floor on
// Netty, servletTest micronaut-servlet's Jetty server on the newest and servlet30Test on the floor,
// each servlet set aligned to its core release by the Micronaut BOM. lambdaTest drives micronaut-aws
// 3.17's Lambda handler on the Micronaut release that aws release was built against, lambda30Test
// the 3.0.0 floor of both.
fun registerMicronautTests(
    setName: String,
    micronautVersion: String,
    servletVersion: String? = null,
    lambdaVersion: String? = null,
) {
    val servlet = servletVersion != null
    val lambda = lambdaVersion != null
    val sourceSet =
        if (setName == "test") {
            sourceSets.getByName("test")
        } else {
            sourceSets.create(setName) {
                java.srcDir("src/test/java")
                kotlin.srcDir("src/test/kotlin")
                if (servlet) {
                    kotlin.srcDir("src/servletTest/kotlin")
                    kotlin.exclude("**/Micronaut3ModuleTest.kt", "**/Micronaut3ModuleMatcherTest.kt")
                }
                if (lambda) {
                    kotlin.srcDir("src/lambdaTest/kotlin")
                    kotlin.exclude("**/Micronaut3ModuleTest.kt", "**/Micronaut3ModuleMatcherTest.kt", "**/DispatchPointGateTest.kt")
                }
                compileClasspath += sourceSets.main.get().output
                runtimeClasspath += sourceSets.main.get().output
            }
        }
    val prefix = if (setName == "test") "test" else setName
    val implementation = configurations.getByName("${prefix}Implementation")
    configurations.getByName("${prefix}RuntimeOnly")
    if (setName != "test") {
        implementation.extendsFrom(configurations.getByName("implementation"))
    }
    dependencies {
        add(implementation.name, project(":endpoints-api"))
        add(implementation.name, project(":"))
        add(implementation.name, "org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
        add(implementation.name, "net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
        add(implementation.name, "net.bytebuddy:byte-buddy:$byteBuddyVersion")
        if (servlet) {
            add(implementation.name, "io.micronaut.servlet:micronaut-http-server-jetty:$servletVersion")
            add(implementation.name, platform("io.micronaut:micronaut-bom:$micronautVersion"))
        } else if (lambda) {
            add(implementation.name, "io.micronaut.aws:micronaut-function-aws-api-proxy:$lambdaVersion")
            add(implementation.name, "io.micronaut:micronaut-http-client:$micronautVersion")
        } else {
            add(implementation.name, "io.micronaut:micronaut-http-server-netty:$micronautVersion")
        }
        add(implementation.name, "io.micronaut:micronaut-runtime:$micronautVersion")
        add(implementation.name, "io.micronaut:micronaut-websocket:$micronautVersion")
        add(implementation.name, "io.micronaut:micronaut-management:$micronautVersion")
        if (micronautVersion != micronaut3FloorVersion) {
            add(implementation.name, "io.micronaut:micronaut-jackson-databind:$micronautVersion")
        }
        add(implementation.name, "org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
        add(implementation.name, "org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$coroutinesVersion")
        add(implementation.name, "org.slf4j:slf4j-nop:$slf4jVersion")
        val kaptName = if (setName == "test") "kaptTest" else "kapt${setName.replaceFirstChar { it.uppercase() }}"
        add(kaptName, "io.micronaut:micronaut-inject-java:$micronautVersion")
    }
    val testTask =
        if (setName == "test") {
            tasks.named<Test>("test")
        } else {
            tasks.register<Test>(setName) {
                description =
                    "Runs the tests against Micronaut $micronautVersion${if (servletVersion != null) " on micronaut-servlet $servletVersion" else ""}${if (lambdaVersion != null) " on micronaut-aws $lambdaVersion" else ""}."
                group = "verification"
                testClassesDirs = sourceSet.output.classesDirs
                classpath = sourceSet.runtimeClasspath
            }
        }
    testTask.configure {
        useJUnitPlatform()
        jvmArgs("-Djdk.attach.allowAttachSelf=true")
        val jars =
            mapOf(
                "otherlode.micronaut3Floor.jars" to micronaut3FloorJars,
                "otherlode.micronaut4Floor.jars" to micronaut4FloorJars,
                "otherlode.micronaut4Newest.jars" to micronaut4NewestJars,
            )
        jars.forEach { (property, files) ->
            inputs.files(files).withPropertyName(property)
            doFirst { systemProperty(property, files.asPath) }
        }
    }
    tasks.check { dependsOn(testTask) }
}

registerMicronautTests("test", micronaut3Version)
registerMicronautTests("micronaut30Test", micronaut3FloorVersion)
registerMicronautTests("servletTest", micronaut3Version, newestServletVersion)
registerMicronautTests("servlet30Test", micronaut3FloorVersion, "3.0.0")
registerMicronautTests("lambdaTest", "3.8.8", lambdaVersion = "3.17.0")
registerMicronautTests("lambda30Test", micronaut3FloorVersion, lambdaVersion = "3.0.0")

import org.gradle.api.tasks.bundling.Jar
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.2.21"
    `jvm-test-suite`
    id("com.gradleup.shadow") version "8.3.11"
    // 0.18.2 is the newest release (2026-09-02). It reads Kotlin 2.1 and later metadata, which
    // covers this module's 2.2.21, and its only change since 0.18.1 is a configuration crash fix.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.2"
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

dependencies {
    // The wire models and codec, and nothing else of the agent's: the agent itself reaches a test
    // JVM only as its shaded -javaagent jar, never as classes on the test classpath.
    implementation(project(":wire"))

    // compileOnly: an adopter who does not use JUnit pays nothing for OtherlodeExtension, the same
    // shape OpenTelemetry uses for opentelemetry-sdk-testing. JUnit's own launcher supplies the
    // real jar at test time for anyone who does add it. Pinned to the version already resolved
    // on this module's test classpath (checked with `./gradlew :testkit:dependencies
    // --configuration testRuntimeClasspath`), so main and test code compile against the same API.
    compileOnly("org.junit.jupiter:junit-jupiter-api:5.10.1")

    testImplementation(kotlin("test"))

    // The end-to-end tests drive the agent's instrumentation and exporter in-process.
    testImplementation(project(":"))

    // byte-buddy-agent gives the end-to-end test ByteBuddyAgent.install() to self-attach.
    // Plain byte-buddy is needed too: OtherlodeInstrumentation.install()/uninstall() are typed in
    // terms of net.bytebuddy.agent.builder.ResettableClassFileTransformer, which is only an
    // implementation (not api) dependency of the root project and so does not arrive on this
    // module's compile classpath transitively.
    testImplementation("net.bytebuddy:byte-buddy-agent:1.18.12")
    testImplementation("net.bytebuddy:byte-buddy:1.18.12")

    // Gives the endpoint end-to-end test JdkHttpServerModule, the real HttpServer endpoint module.
    testImplementation(project(":endpoints-jdk-httpserver"))

    // EndpointModule is only an implementation dependency of :endpoints-jdk-httpserver, so it does
    // not arrive transitively; needed directly for EndpointInstrumentation's List<EndpointModule>.
    testImplementation(project(":endpoints-api"))
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// The plain jar keeps its own classifier so it cannot overwrite the shadow jar, which is the
// testkit artifact. Both carry the version the agent's jar carries: the testkit reads it back from
// its package to refuse an agent of another version.
tasks.jar {
    archiveBaseName.set("otherlode-testkit")
    archiveClassifier.set("plain")
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

// Licence texts the shadow jar carries under META-INF, taken from the files the agent jar already
// uses: the repository's LICENSE and the protobuf-java text under licenses/. The NOTICE is this
// module's own.
val bundledLicenseFiles =
    mapOf(
        "META-INF/LICENSE" to rootProject.layout.projectDirectory.file("LICENSE"),
        "META-INF/NOTICE" to layout.projectDirectory.file("NOTICE"),
        "META-INF/licenses/protobuf-java/LICENSE" to rootProject.layout.projectDirectory.file("licenses/protobuf-java/LICENSE"),
    )

tasks.shadowJar {
    archiveBaseName.set("otherlode-testkit")
    archiveClassifier.set("")

    // kotlin-stdlib is the testkit's one runtime dependency; the adopter's own copy serves it.
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
        exclude(dependency("org.jetbrains:annotations"))
    }

    // The wire module and protobuf-java travel inside the jar under the testkit's own prefix, so a
    // test classpath that carries another protobuf, or the wire module at another version, never
    // meets these copies.
    relocate("com.google.protobuf", "dev.otherlode.testkit.shaded.protobuf")
    relocate("dev.otherlode.export", "dev.otherlode.testkit.shaded.export")
    relocate("dev.otherlode.proto", "dev.otherlode.testkit.shaded.proto")
    relocate("dev.otherlode.registry", "dev.otherlode.testkit.shaded.registry")

    // The schema files protobuf-java and the wire module ship as resources would otherwise sit
    // unrelocated on an adopter's classpath; the generated classes carry their own descriptors.
    exclude("google/protobuf/**", "otherlode/**")

    // META-INF/LICENSE and META-INF/NOTICE must describe this jar, not whichever dependency
    // shadow copied first, so every dependency's own copies are dropped and the files above go in.
    exclude("META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/licenses/**")
    for ((entry, source) in bundledLicenseFiles) {
        from(source) {
            into(entry.substringBeforeLast('/'))
            rename { entry.substringAfterLast('/') }
        }
    }

    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// Fails the build if the shadow jar is not what an adopter's test classpath can take: only the
// testkit's own classes, protobuf and the wire module present only relocated, no copy of
// kotlin-stdlib, and the licence files in place.
val verifyTestkitJar by tasks.registering {
    dependsOn(tasks.shadowJar)
    val jarFile = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    inputs.property("licenseEntries", bundledLicenseFiles.keys.sorted())
    doLast {
        ZipFile(jarFile.get().asFile).use { zip ->
            val names =
                zip
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .toList()
            val classes = names.filter { it.endsWith(".class") }
            val foreign = classes.filterNot { it.startsWith("dev/otherlode/testkit/") }
            check(foreign.isEmpty()) { "testkit jar carries classes outside dev/otherlode/testkit/: ${foreign.take(10)}" }
            val unrelocated =
                names.filter {
                    it.startsWith("com/google/protobuf/") ||
                        it.startsWith("dev/otherlode/export/") ||
                        it.startsWith("dev/otherlode/proto/") ||
                        it.startsWith("dev/otherlode/registry/")
                }
            check(unrelocated.isEmpty()) { "testkit jar carries unrelocated entries: ${unrelocated.take(10)}" }
            val schemaFiles = names.filter { it.endsWith(".proto") }
            check(schemaFiles.isEmpty()) { "testkit jar carries unrelocated schema files: $schemaFiles" }
            val kotlinClasses = names.filter { it.startsWith("kotlin/") }
            check(kotlinClasses.isEmpty()) { "testkit jar must not bundle kotlin-stdlib, found: ${kotlinClasses.take(10)}" }
            check(classes.any { it.startsWith("dev/otherlode/testkit/shaded/protobuf/") }) {
                "testkit jar is missing the relocated protobuf-java"
            }
            check(classes.any { it.startsWith("dev/otherlode/testkit/shaded/export/") }) {
                "testkit jar is missing the relocated wire module"
            }
            val missing = bundledLicenseFiles.keys.filterNot { it in names }
            check(missing.isEmpty()) { "testkit jar is missing licence entries: $missing" }
            val notice = zip.getInputStream(zip.getEntry("META-INF/NOTICE")).bufferedReader().use { it.readLine() }
            check(notice == "Otherlode testkit") { "testkit jar's META-INF/NOTICE is not this module's own; first line is \"$notice\"" }
            val manifest = zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF")).bufferedReader().readText()
            check("Implementation-Version: ${project.version}" in manifest) { "testkit jar's manifest does not carry the project version" }
        }
    }
}

// A Java caller of every public query, compiled and never run: it fails the build when a change
// makes the API unusable from Java.
val javaApiTest = sourceSets.create("javaApiTest")
dependencies {
    "javaApiTestImplementation"(sourceSets.main.get().output)
    "javaApiTestImplementation"(kotlin("stdlib"))
    "javaApiTestCompileOnly"("org.junit.jupiter:junit-jupiter-api:5.10.1")
}

// The Scala fixture modules, wired in the way the root build wires them: a task dependency and two
// system properties each, never a test dependency, so no fixture class loads before a test installs
// instrumentation. The never-hit test for Scala's generated methods loads them through a child-first
// loader.
evaluationDependsOn(":fixtures-scala3")
evaluationDependsOn(":fixtures-scala2")
val scalaFixtureModules = listOf("scala3", "scala2")

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    dependsOn(scalaFixtureModules.map { ":fixtures-$it:classes" })
    doFirst {
        for (module in scalaFixtureModules) {
            val fixture = project(":fixtures-$module")
            systemProperty(
                "otherlode.fixtures.$module.dir",
                fixture.layout.buildDirectory
                    .dir("classes/scala/main")
                    .get()
                    .asFile.absolutePath,
            )
            systemProperty(
                "otherlode.fixtures.$module.classpath",
                fixture.configurations
                    .named("runtimeClasspath")
                    .get()
                    .asPath,
            )
        }
    }
}

// Proves OtherlodeExtension against the real, shaded agent jar rather than hand-built payloads or a
// self-attach. A distinct port (4329) and a distinct source set keep this suite from colliding
// with anything an adopter runs on the agent's own default port (4319), and from putting
// -javaagent on the plain `test` suite above, whose tests self-attach instead and must stay
// that way.
val agentTestCollectorPort = 4329

// Two one-class jars for the agentTest suite's dependency test, built here rather than taken from
// the suite's own classpath so which one loads is fully under the test's control. dep-used carries
// a pom.properties and is called from com.example.agenttarget; dep-unused has none, so its
// identity comes from the filename with an empty group, and nothing ever loads it.
val depUsed = sourceSets.create("depUsed")
val depUnused = sourceSets.create("depUnused")
val fixtureDependencyDir = layout.buildDirectory.dir("fixture-dependencies")
val depUsedJar by tasks.registering(Jar::class) {
    archiveFileName.set("dep-used-1.0.jar")
    destinationDirectory.set(fixtureDependencyDir)
    from(depUsed.output)
}
val depUnusedJar by tasks.registering(Jar::class) {
    archiveFileName.set("dep-unused-1.0.jar")
    destinationDirectory.set(fixtureDependencyDir)
    from(depUnused.output)
}

testing {
    suites {
        register<JvmTestSuite>("agentTest") {
            useJUnitJupiter("5.10.1")
            sources {
                kotlin.srcDir("src/agentTest/kotlin")
            }
            dependencies {
                // A registered suite, unlike the built-in `test` suite, does not inherit this
                // project's own main output automatically: project() gives it OtherlodeTestCollector
                // and OtherlodeExtension. kotlin("test") is a build-script-scoped helper, not
                // available inside a suite's own dependencies block, so this names the same
                // artifact directly, as the endpoints-spring-webmvc and endpoints-jaxrs suites
                // already do.
                // The shadow jar, so the relocation and the manifest version are exercised exactly as
                // an adopter's classpath sees them. kotlin-stdlib arrives through kotlin-test-junit5.
                implementation(files(tasks.shadowJar))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.21")
                implementation(files(depUsedJar, depUnusedJar))
            }
            targets {
                all {
                    testTask.configure {
                        dependsOn(rootProject.tasks.named("shadowJar"))
                        doFirst {
                            val agentJar =
                                rootProject.tasks
                                    .named("shadowJar", Jar::class.java)
                                    .get()
                                    .archiveFile
                                    .get()
                                    .asFile
                            jvmArgs(
                                "-javaagent:${agentJar.absolutePath}=" +
                                    "includePackages=com.example.agenttarget," +
                                    "flushIntervalSeconds=1," +
                                    "staticBaselineEnabled=true," +
                                    "serviceName=testkit-agent-test," +
                                    "endpointsEnabled=false," +
                                    "exportUrl=http://localhost:$agentTestCollectorPort",
                                "-Dotherlode.testkit.port=$agentTestCollectorPort",
                            )
                        }
                    }
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("agentTest"))
    dependsOn(verifyTestkitJar)
    dependsOn(tasks.named("compileJavaApiTestJava"))
}
